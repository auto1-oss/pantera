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

import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link SuggestQuery}: words, substring matching, ranking, LIKE patterns.
 *
 * @since 2.2.9
 */
final class SuggestQueryTest {

    @Test
    void splitsOnWhitespaceOnly() {
        MatcherAssert.assertThat(
            new SuggestQuery("  Jackson   types/NO @scope-x ").words(),
            new IsEqual<>(List.of("jackson", "types/no", "@scope-x"))
        );
    }

    @Test
    void aSingleWordIsAPlainSubstring() {
        final SuggestQuery query = new SuggestQuery("http5");
        MatcherAssert.assertThat(
            "found in the middle of a word",
            query.matches(List.of("com.example.okhttp5-client")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "case-insensitive",
            query.matches(List.of("OkHttp5")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "absent",
            query.matches(List.of("okhttp4")),
            new IsEqual<>(false)
        );
    }

    @Test
    void separatorsInAWordAreLiteral() {
        final SuggestQuery query = new SuggestQuery("types/no");
        MatcherAssert.assertThat(
            "the scoped name contains it",
            query.matches(List.of("@types/node")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "not split into sub-tokens",
            query.matches(List.of("types-nothing/other")),
            new IsEqual<>(false)
        );
    }

    @Test
    void everyWordMustAppearInSomeForm() {
        final SuggestQuery query = new SuggestQuery("jackson databind");
        MatcherAssert.assertThat(
            "both words present",
            query.matches(List.of("com.fasterxml.jackson.core.jackson-databind")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "words may come from different spellings",
            new SuggestQuery("core:jackson databind").matches(
                List.of("com.fasterxml.jackson.core.jackson-databind",
                    "com.fasterxml.jackson.core:jackson-databind")
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "one word missing",
            query.matches(List.of("com.fasterxml.jackson.core.jackson-core")),
            new IsEqual<>(false)
        );
    }

    @Test
    void ranksExactPrefixTokenStartSubstring() {
        final SuggestQuery query = new SuggestQuery("databind");
        MatcherAssert.assertThat(
            "exact on the full name",
            query.rank(List.of("databind")),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "exact on the simple name",
            query.rank(List.of("com.example:databind")),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "prefix",
            query.rank(List.of("databind-extra")),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "token start",
            query.rank(List.of("com.fasterxml.jackson.core:jackson-databind")),
            new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "substring",
            query.rank(List.of("nodatabinder")),
            new IsEqual<>(4)
        );
    }

    @Test
    void exactIgnoresSeparatorSpelling() {
        MatcherAssert.assertThat(
            "words match the scoped name exactly",
            new SuggestQuery("@types node").rank(List.of("@types/node")),
            new IsEqual<>(0)
        );
    }

    @Test
    void patternsEscapeWildcardsAndWidenSeparators() {
        MatcherAssert.assertThat(
            "literal pattern first, separators widened to one-character wildcards second",
            new SuggestQuery("a%b_c:d").patterns(),
            new IsEqual<>(List.of(List.of("%a\\%b\\_c:d%", "%a\\%b_c_d%")))
        );
        MatcherAssert.assertThat(
            "no separator, one pattern",
            new SuggestQuery("http5").patterns(),
            new IsEqual<>(List.of(List.of("%http5%")))
        );
    }

    @Test
    void blankQueryHasNoWords() {
        MatcherAssert.assertThat(new SuggestQuery("   ").empty(), new IsEqual<>(true));
    }
}
