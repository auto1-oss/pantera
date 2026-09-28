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
package com.auto1.pantera.api.v1.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Type-ahead suggestions for the cooldown package inspector: package names
 * from the artifacts index and the cooldown records that match what the
 * operator typed, in the form the inspector accepts (maven
 * {@code groupId:artifactId}, pypi PEP 503 normalised, others as stored).
 * Blocking (JDBC); call off the event loop.
 *
 * @since 2.2.9
 */
public final class PackageSuggester {

    /**
     * Candidate rows fetched per source before matching and ranking.
     */
    static final int CANDIDATES = 500;

    /**
     * PEP 503 separator runs.
     */
    private static final Pattern PYPI = Pattern.compile("[-_.]+");

    /**
     * Name-part separators, read as word breaks by the did-you-mean retry.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[-_./:@]+");

    /**
     * Candidate source.
     */
    private final SuggestLookup lookup;

    /**
     * Ctor.
     *
     * @param lookup Candidate source
     */
    public PackageSuggester(final SuggestLookup lookup) {
        this.lookup = lookup;
    }

    /**
     * Suggestions.
     *
     * @param repoType Format family or repository type; null or blank for all
     * @param text Text as typed
     * @param limit Maximum suggestions
     * @return Suggestions, closest first
     */
    public JsonArray suggest(final String repoType, final String text, final int limit) {
        return PackageSuggester.json(this.find(repoType, text, limit));
    }

    /**
     * Suggestions for a name the inspector found nothing for: the name as a
     * search, then — when that finds nothing — with its separators read as
     * word breaks. The typed name itself is left out.
     *
     * @param repoType Format family
     * @param typed Name as inspected
     * @param max Maximum suggestions
     * @return Suggestions, closest first
     */
    public JsonArray didYouMean(final String repoType, final String typed, final int max) {
        List<Suggestion> found = this.others(repoType, typed, typed, max);
        if (found.isEmpty()) {
            final String words = SEPARATORS.matcher(typed == null ? "" : typed).replaceAll(" ");
            found = this.others(repoType, words, typed, max);
        }
        return PackageSuggester.json(found);
    }

    /**
     * Matching packages.
     *
     * @param repoType Format family or repository type; null or blank for all
     * @param text Text as typed
     * @param limit Maximum suggestions
     * @return Suggestions, closest first
     */
    List<Suggestion> find(final String repoType, final String text, final int limit) {
        final SuggestQuery query = new SuggestQuery(text);
        if (query.empty() || limit <= 0) {
            return List.of();
        }
        final List<SuggestRow> rows = this.lookup.rows(
            PackageSuggester.families(repoType), query, CANDIDATES
        );
        final Set<String> unresolved = rows.stream()
            .filter(row -> PackageSuggester.maven(PackageSuggester.family(row))
                && new MavenCoordinate(row.name(), row.pathPrefix()).coordinate().isEmpty())
            .map(SuggestRow::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        final Map<String, String> paths = unresolved.isEmpty()
            ? Map.of() : this.lookup.mavenPaths(unresolved);
        final Map<String, Suggestion> merged = new LinkedHashMap<>();
        for (final SuggestRow row : rows) {
            final Suggestion cand = PackageSuggester.describe(row, paths);
            if (query.matches(cand.forms())) {
                merged.merge(cand.key(), cand, Suggestion::absorb);
            }
        }
        final List<Suggestion> out = new ArrayList<>(merged.values());
        out.forEach(sug -> sug.ranked(query.rank(sug.forms())));
        out.sort(
            Comparator.comparingInt(Suggestion::rank)
                .thenComparing(sug -> sug.pkg().toLowerCase(Locale.ROOT))
                .thenComparing(Suggestion::family)
        );
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * Suggestions other than the typed name.
     *
     * @param repoType Format family
     * @param text Search text
     * @param typed Name to leave out
     * @param max Maximum suggestions
     * @return Suggestions
     */
    private List<Suggestion> others(
        final String repoType, final String text, final String typed, final int max
    ) {
        return this.find(repoType, text, max + 1).stream()
            .filter(sug -> !sug.pkg().equalsIgnoreCase(typed == null ? "" : typed.trim()))
            .limit(max)
            .collect(Collectors.toList());
    }

    /**
     * One row as a suggestion.
     *
     * @param row Row
     * @param paths Index paths of dotted maven names
     * @return Suggestion
     */
    private static Suggestion describe(final SuggestRow row, final Map<String, String> paths) {
        final String family = PackageSuggester.family(row);
        final String pkg;
        final String display;
        if (PackageSuggester.maven(family)) {
            final Optional<String> coord = new MavenCoordinate(row.name(), row.pathPrefix())
                .coordinate()
                .or(() -> new MavenCoordinate(row.name(), paths.get(row.name())).coordinate());
            pkg = coord.orElse(row.name());
            display = coord.isPresent() ? pkg
                : row.name() + " (groupId:artifactId not in the index)";
        } else if ("pypi".equals(family)) {
            pkg = PYPI.matcher(row.name()).replaceAll("-").toLowerCase(Locale.ROOT);
            display = pkg;
        } else {
            pkg = row.name();
            display = pkg;
        }
        return new Suggestion(family, pkg, display, row);
    }

    /**
     * Families a type filter covers.
     *
     * @param repoType Family or type; null or blank for all
     * @return Families, empty for all
     */
    private static List<String> families(final String repoType) {
        final List<String> out;
        if (repoType == null || repoType.isBlank()) {
            out = List.of();
        } else {
            final String family = new PackageName(repoType.trim(), "").family();
            out = PackageSuggester.maven(family) ? List.of("gradle", "maven") : List.of(family);
        }
        return out;
    }

    /**
     * Family of a row.
     *
     * @param row Row
     * @return Family
     */
    private static String family(final SuggestRow row) {
        return new PackageName(row.repoType(), "").family();
    }

    /**
     * Whether a family uses the maven layout.
     *
     * @param family Family
     * @return True for maven and gradle
     */
    private static boolean maven(final String family) {
        return "maven".equals(family) || "gradle".equals(family);
    }

    /**
     * Suggestions as JSON.
     *
     * @param list Suggestions
     * @return JSON array
     */
    private static JsonArray json(final List<Suggestion> list) {
        final JsonArray out = new JsonArray();
        for (final Suggestion sug : list) {
            out.add(
                new JsonObject()
                    .put("package", sug.pkg())
                    .put("display", sug.display())
                    .put("repoType", sug.family())
                    .put("sources", new JsonArray(
                        List.of(SuggestRow.INDEX, SuggestRow.COOLDOWN).stream()
                            .filter(sug.sources()::contains).collect(Collectors.toList())
                    ))
                    .put("repos", new JsonArray(new ArrayList<>(sug.repos())))
            );
        }
        return out;
    }

    /**
     * One suggested package, merged over its rows.
     *
     * @since 2.2.9
     */
    static final class Suggestion {

        /**
         * Format family.
         */
        private final String family;

        /**
         * Package as the inspector accepts it.
         */
        private final String pkg;

        /**
         * Human form.
         */
        private final String display;

        /**
         * Stored names.
         */
        private final Set<String> names;

        /**
         * Sources.
         */
        private final Set<String> sources;

        /**
         * Repositories.
         */
        private final Set<String> repos;

        /**
         * Rank, set once merged.
         */
        private int rank;

        /**
         * Ctor.
         *
         * @param family Family
         * @param pkg Package
         * @param display Human form
         * @param row First row
         */
        Suggestion(final String family, final String pkg, final String display, final SuggestRow row) {
            this.family = family;
            this.pkg = pkg;
            this.display = display;
            this.names = new LinkedHashSet<>();
            this.sources = new LinkedHashSet<>();
            this.repos = new TreeSet<>();
            this.names.add(row.name());
            this.sources.add(row.source());
            this.repos.add(row.repoName());
        }

        /**
         * Family.
         *
         * @return Family
         */
        String family() {
            return this.family;
        }

        /**
         * Package as the inspector accepts it.
         *
         * @return Package
         */
        String pkg() {
            return this.pkg;
        }

        /**
         * Human form.
         *
         * @return Display
         */
        String display() {
            return this.display;
        }

        /**
         * Stored names.
         *
         * @return Names
         */
        Set<String> names() {
            return this.names;
        }

        /**
         * Sources.
         *
         * @return Sources
         */
        Set<String> sources() {
            return this.sources;
        }

        /**
         * Repositories.
         *
         * @return Sorted repository names
         */
        Set<String> repos() {
            return this.repos;
        }

        /**
         * Rank.
         *
         * @return Rank, lower is closer
         */
        int rank() {
            return this.rank;
        }

        /**
         * Set the rank.
         *
         * @param value Rank
         */
        void ranked(final int value) {
            this.rank = value;
        }

        /**
         * Spellings to match and rank on.
         *
         * @return Stored names and the package form
         */
        List<String> forms() {
            final List<String> out = new ArrayList<>(this.names);
            out.add(this.pkg);
            return out;
        }

        /**
         * Merge key.
         *
         * @return Family and package
         */
        String key() {
            return this.family + '\n' + this.pkg;
        }

        /**
         * Take another row's names, sources and repositories.
         *
         * @param other Suggestion of the same package
         * @return This
         */
        Suggestion absorb(final Suggestion other) {
            this.names.addAll(other.names);
            this.sources.addAll(other.sources);
            this.repos.addAll(other.repos);
            return this;
        }
    }
}
