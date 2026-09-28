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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What an operator typed into the inspector's package search.
 *
 * <p>The text is split on whitespace into words; a name matches when every
 * word is a case-insensitive substring of one of its spellings. A word is
 * matched literally — {@code types/no} must appear as typed — so a
 * single-word query is the same {@code ILIKE '%word%'} the cooldown list
 * and the global package search use, and its results are a superset of
 * theirs.</p>
 *
 * @since 2.2.9
 */
public final class SuggestQuery {

    /**
     * Word separator.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Name-part separators, for ranking and pattern widening.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_./:@]+");

    /**
     * Rank of a name equal to the query.
     */
    private static final int EXACT = 0;

    /**
     * Rank of a name whose last segment equals the query.
     */
    private static final int SIMPLE_EXACT = 1;

    /**
     * Rank of a name starting with the query.
     */
    private static final int PREFIX = 2;

    /**
     * Rank of a name where every word starts a name part.
     */
    private static final int TOKEN_START = 3;

    /**
     * Rank of any other match.
     */
    private static final int SUBSTRING = 4;

    /**
     * Text as typed, trimmed and lower case.
     */
    private final String text;

    /**
     * Distinct lower-case words.
     */
    private final List<String> words;

    /**
     * Ctor.
     *
     * @param raw Text as typed, may be null
     */
    public SuggestQuery(final String raw) {
        this.text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        this.words = this.text.isEmpty() ? List.of()
            : Arrays.stream(WHITESPACE.split(this.text)).filter(word -> !word.isEmpty())
                .distinct().collect(Collectors.toUnmodifiableList());
    }

    /**
     * Text, trimmed and lower case.
     *
     * @return Text
     */
    public String text() {
        return this.text;
    }

    /**
     * Words.
     *
     * @return Distinct lower-case words
     */
    public List<String> words() {
        return this.words;
    }

    /**
     * Whether there is nothing to search for.
     *
     * @return True for blank text
     */
    public boolean empty() {
        return this.words.isEmpty();
    }

    /**
     * SQL {@code ILIKE ... ESCAPE '\'} alternatives per word. The first is
     * the literal word; when the word contains separators, the second turns
     * each separator into a one-character wildcard so a stored spelling with
     * other separators ({@code com.example.foo} for {@code example:foo},
     * {@code typing_extensions} for {@code typing-ext}) is fetched too. The
     * caller re-checks the fetched names with {@link #matches(Collection)}.
     *
     * @return One list of patterns per word
     */
    public List<List<String>> patterns() {
        final List<List<String>> out = new ArrayList<>(this.words.size());
        for (final String word : this.words) {
            final List<String> alts = new ArrayList<>(2);
            alts.add("%" + SuggestQuery.escape(word) + "%");
            final StringBuilder wide = new StringBuilder("%");
            boolean widened = false;
            for (final char chr : word.toCharArray()) {
                if (SuggestQuery.separator(chr) && chr != '@') {
                    wide.append('_');
                    widened = true;
                } else {
                    wide.append(SuggestQuery.escape(String.valueOf(chr)));
                }
            }
            if (widened) {
                alts.add(wide.append('%').toString());
            }
            out.add(alts);
        }
        return out;
    }

    /**
     * SQL {@code LIKE ... ESCAPE '\'} pattern of names starting with the
     * whole text, for ordering candidates.
     *
     * @return Prefix pattern
     */
    public String prefixPattern() {
        return SuggestQuery.escape(this.text) + "%";
    }

    /**
     * Whether every word is a substring of at least one spelling.
     *
     * @param forms Spellings of one package
     * @return True on a match
     */
    public boolean matches(final Collection<String> forms) {
        final List<String> lower = SuggestQuery.lower(forms);
        return !this.words.isEmpty() && this.words.stream().allMatch(
            word -> lower.stream().anyMatch(form -> form.contains(word))
        );
    }

    /**
     * Rank of a matching package: 0 the whole name equals the text, 1 its
     * last segment (maven artifactId, npm name after the scope) does, 2 it
     * starts with the text, 3 every word starts a name part, 4 otherwise.
     * Separators are ignored for equality and prefix.
     *
     * @param forms Spellings of one package
     * @return Rank, lower is closer
     */
    public int rank(final Collection<String> forms) {
        final List<String> lower = SuggestQuery.lower(forms);
        final String want = SuggestQuery.canonical(this.text);
        final int result;
        if (lower.stream().anyMatch(form -> SuggestQuery.canonical(form).equals(want))) {
            result = EXACT;
        } else if (lower.stream().anyMatch(
            form -> SuggestQuery.canonical(SuggestQuery.simple(form)).equals(want)
        )) {
            result = SIMPLE_EXACT;
        } else if (lower.stream().anyMatch(
            form -> SuggestQuery.canonical(form).startsWith(want)
                || SuggestQuery.canonical(SuggestQuery.simple(form)).startsWith(want)
        )) {
            result = PREFIX;
        } else if (this.words.stream().allMatch(
            word -> lower.stream().anyMatch(form -> SuggestQuery.startsPart(form, word))
        )) {
            result = TOKEN_START;
        } else {
            result = SUBSTRING;
        }
        return result;
    }

    /**
     * Escape LIKE wildcards and the escape character.
     *
     * @param value Value
     * @return Escaped value
     */
    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Whether a character separates name parts.
     *
     * @param chr Character
     * @return True for {@code - _ . / : @} and whitespace
     */
    private static boolean separator(final char chr) {
        return SEPARATORS.matcher(String.valueOf(chr)).matches();
    }

    /**
     * Whether a word occurs at the start of the name or right after a
     * separator.
     *
     * @param form Lower-case name
     * @param word Word
     * @return True when it starts a name part
     */
    private static boolean startsPart(final String form, final String word) {
        int idx = form.indexOf(word);
        boolean found = false;
        while (idx >= 0 && !found) {
            found = idx == 0 || SuggestQuery.separator(form.charAt(idx - 1));
            idx = form.indexOf(word, idx + 1);
        }
        return found;
    }

    /**
     * Last segment of a name: after the last {@code :} or {@code /}.
     *
     * @param form Name
     * @return Segment
     */
    private static String simple(final String form) {
        return form.substring(Math.max(form.lastIndexOf(':'), form.lastIndexOf('/')) + 1);
    }

    /**
     * Comparison shape: separator runs collapsed to {@code -}, trimmed.
     *
     * @param value Lower-case value
     * @return Canonical value
     */
    private static String canonical(final String value) {
        return SEPARATORS.matcher(value).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    /**
     * Lower-case the non-null spellings.
     *
     * @param forms Spellings
     * @return Lower-case spellings
     */
    private static List<String> lower(final Collection<String> forms) {
        return forms.stream().filter(form -> form != null && !form.isEmpty())
            .map(form -> form.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
    }
}
