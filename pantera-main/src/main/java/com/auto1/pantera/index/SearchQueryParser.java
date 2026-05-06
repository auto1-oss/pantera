/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured search queries into FTS terms and typed field filters.
 * <p>
 * Supports syntax like:
 * <pre>
 *   name:pydantic AND (version:2.12 OR version:2.11) AND repo:pypi-proxy
 * </pre>
 * <p>
 * Syntax rules:
 * <ul>
 *   <li>{@code name:value} - filter by artifact_name (ILIKE)</li>
 *   <li>{@code version:value} - filter by version (ILIKE)</li>
 *   <li>{@code repo:value} - filter by repo_name (exact match)</li>
 *   <li>{@code type:value} - filter by repo_type (prefix match, strips -proxy/-group)</li>
 *   <li>Bare terms (no prefix) - full-text search via existing FTS</li>
 *   <li>{@code AND} - default operator between space-separated terms</li>
 *   <li>{@code OR} - explicit, groups values for the same field</li>
 *   <li>Parentheses - grouping for OR precedence</li>
 * </ul>
 * <p>
 * Backward compatible: {@code "pydantic 2.12"} (no prefixes) = pure FTS as today.
 *
 * @since 2.1.0
 */
public final class SearchQueryParser {

    /**
     * Known field names mapped to their match types.
     */
    private static final Map<String, MatchType> FIELD_MATCH_TYPES = Map.of(
        "name", MatchType.ILIKE,
        "version", MatchType.ILIKE,
        "repo", MatchType.EXACT,
        "type", MatchType.PREFIX
    );

    /**
     * Known field names set for fast lookup.
     */
    private static final Set<String> KNOWN_FIELDS = FIELD_MATCH_TYPES.keySet();

    /**
     * Pattern for tokenizing: matches quoted strings, parentheses, or
     * non-whitespace sequences.
     */
    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\"([^\"]*)\"|([()])|([^\\s()]+)");

    /**
     * Match type for field filters.
     */
    public enum MatchType {
        /** Case-insensitive LIKE match (wraps value with %). */
        ILIKE,
        /** Exact equality match. */
        EXACT,
        /** Prefix match (appends % only). */
        PREFIX
    }

    /**
     * A single field filter extracted from the query.
     *
     * @param field Field name: "name", "version", "repo", or "type"
     * @param values One or more values (OR within a field)
     * @param matchType How the value should be matched in SQL
     */
    public record FieldFilter(
        String field,
        List<String> values,
        MatchType matchType
    ) { }

    /**
     * Parsed search query containing FTS terms and structured field filters.
     *
     * @param ftsQuery Bare terms for ts_vector FTS (may be empty)
     * @param filters Structured field filters (may be empty)
     */
    public record SearchQuery(
        String ftsQuery,
        List<FieldFilter> filters
    ) {
        /** Empty search query constant. */
        public static final SearchQuery EMPTY = new SearchQuery("", List.of());
    }

    /**
     * Private constructor — utility class.
     */
    private SearchQueryParser() {
    }

    /**
     * Parse a raw search query string into a structured {@link SearchQuery}.
     *
     * @param input Raw query string (may be null or empty)
     * @return Parsed SearchQuery, never null
     */
    public static SearchQuery parse(final String input) {
        if (input == null || input.isBlank()) {
            return SearchQuery.EMPTY;
        }
        final List<String> tokens = tokenize(input);
        final List<String> ftsTerms = new ArrayList<>();
        // field -> list of values (preserves insertion order)
        final Map<String, List<String>> fieldValues = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            final String token = tokens.get(i);
            // Skip AND/OR keywords and parentheses — they are handled implicitly
            if ("AND".equals(token) || "OR".equals(token)
                || "(".equals(token) || ")".equals(token)) {
                continue;
            }
            final int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1) {
                // field:value (no space after colon)
                final String field = token.substring(0, colon).toLowerCase(Locale.ROOT);
                final String value = token.substring(colon + 1);
                if (KNOWN_FIELDS.contains(field)) {
                    fieldValues.computeIfAbsent(field, k -> new ArrayList<>())
                        .add(normalizeValue(field, value));
                } else {
                    ftsTerms.add(token);
                }
            } else if (colon > 0 && colon == token.length() - 1) {
                // field: value (space after colon) — peek at next non-operator token
                final String field = token.substring(0, colon).toLowerCase(Locale.ROOT);
                if (KNOWN_FIELDS.contains(field) && i + 1 < tokens.size()) {
                    final String next = tokens.get(i + 1);
                    if (!"AND".equals(next) && !"OR".equals(next)
                        && !"(".equals(next) && !")".equals(next)) {
                        fieldValues.computeIfAbsent(field, k -> new ArrayList<>())
                            .add(normalizeValue(field, next));
                        i++;
                        continue;
                    }
                }
                ftsTerms.add(token);
            } else {
                ftsTerms.add(token);
            }
        }
        final List<FieldFilter> filters = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : fieldValues.entrySet()) {
            filters.add(new FieldFilter(
                entry.getKey(),
                List.copyOf(entry.getValue()),
                FIELD_MATCH_TYPES.get(entry.getKey())
            ));
        }
        final String ftsQuery = String.join(" ", ftsTerms);
        return new SearchQuery(ftsQuery, List.copyOf(filters));
    }

    /**
     * Normalize a field value. Strips the leading {@code v} or {@code V} from
     * version values since the DB stores bare version numbers (e.g. {@code 0.1.65}
     * not {@code v0.1.65}).
     *
     * @param field Field name
     * @param value Raw value from the query
     * @return Normalized value
     */
    private static String normalizeValue(final String field, final String value) {
        if ("version".equals(field) && value.length() > 1
            && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            return value.substring(1);
        }
        return value;
    }

    /**
     * Tokenize the input string. Respects quoted strings and parentheses
     * as separate tokens.
     *
     * @param input Raw query string
     * @return List of tokens
     */
    private static List<String> tokenize(final String input) {
        final List<String> tokens = new ArrayList<>();
        final Matcher matcher = TOKEN_PATTERN.matcher(input.trim());
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                // Quoted string — keep as-is (without quotes)
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                // Parenthesis
                tokens.add(matcher.group(2));
            } else {
                // Normal token
                tokens.add(matcher.group(3));
            }
        }
        return tokens;
    }
}
