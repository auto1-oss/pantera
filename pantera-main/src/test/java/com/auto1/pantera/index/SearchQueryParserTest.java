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

import com.auto1.pantera.index.SearchQueryParser.FieldFilter;
import com.auto1.pantera.index.SearchQueryParser.MatchType;
import com.auto1.pantera.index.SearchQueryParser.SearchQuery;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link SearchQueryParser}.
 *
 * @since 2.1.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SearchQueryParserTest {

    @Test
    void bareTermsGoToFts() {
        final SearchQuery result = SearchQueryParser.parse("pydantic");
        MatcherAssert.assertThat(
            "bare term should go to ftsQuery",
            result.ftsQuery(),
            new IsEqual<>("pydantic")
        );
        MatcherAssert.assertThat(
            "no filters expected",
            result.filters(),
            Matchers.empty()
        );
    }

    @Test
    void fieldPrefixExtracted() {
        final SearchQuery result = SearchQueryParser.parse("name:pydantic");
        MatcherAssert.assertThat(
            "ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "should have 1 filter",
            result.filters(),
            Matchers.hasSize(1)
        );
        final FieldFilter filter = result.filters().get(0);
        MatcherAssert.assertThat(
            "field should be name",
            filter.field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "values should contain pydantic",
            filter.values(),
            new IsEqual<>(List.of("pydantic"))
        );
        MatcherAssert.assertThat(
            "match type should be ILIKE for name",
            filter.matchType(),
            new IsEqual<>(MatchType.ILIKE)
        );
    }

    @Test
    void multipleFieldsAreAnded() {
        final SearchQuery result = SearchQueryParser.parse("name:pydantic version:2.12");
        MatcherAssert.assertThat(
            "ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "should have 2 filters",
            result.filters(),
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "first filter field",
            result.filters().get(0).field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "second filter field",
            result.filters().get(1).field(),
            new IsEqual<>("version")
        );
    }

    @Test
    void orGroupsValues() {
        final SearchQuery result = SearchQueryParser.parse("version:2.12 OR version:2.11");
        MatcherAssert.assertThat(
            "ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "should have 1 filter with merged values",
            result.filters(),
            Matchers.hasSize(1)
        );
        final FieldFilter filter = result.filters().get(0);
        MatcherAssert.assertThat(
            "field should be version",
            filter.field(),
            new IsEqual<>("version")
        );
        MatcherAssert.assertThat(
            "values should contain both versions",
            filter.values(),
            new IsEqual<>(List.of("2.12", "2.11"))
        );
    }

    @Test
    void parenthesesGroupOr() {
        final SearchQuery result = SearchQueryParser.parse(
            "name:pydantic AND (version:2.12 OR version:2.11)"
        );
        MatcherAssert.assertThat(
            "ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "should have 2 filters",
            result.filters(),
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "first filter should be name",
            result.filters().get(0).field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "first filter values",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("pydantic"))
        );
        MatcherAssert.assertThat(
            "second filter should be version",
            result.filters().get(1).field(),
            new IsEqual<>("version")
        );
        MatcherAssert.assertThat(
            "second filter should have OR'd values",
            result.filters().get(1).values(),
            new IsEqual<>(List.of("2.12", "2.11"))
        );
    }

    @Test
    void mixedBareAndField() {
        final SearchQuery result = SearchQueryParser.parse("pydantic name:foo");
        MatcherAssert.assertThat(
            "ftsQuery should contain bare term",
            result.ftsQuery(),
            new IsEqual<>("pydantic")
        );
        MatcherAssert.assertThat(
            "should have 1 filter",
            result.filters(),
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "filter field should be name",
            result.filters().get(0).field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "filter values",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("foo"))
        );
    }

    @Test
    void emptyInputReturnsEmpty() {
        MatcherAssert.assertThat(
            "empty string returns EMPTY",
            SearchQueryParser.parse(""),
            new IsEqual<>(SearchQuery.EMPTY)
        );
        MatcherAssert.assertThat(
            "null returns EMPTY",
            SearchQueryParser.parse(null),
            new IsEqual<>(SearchQuery.EMPTY)
        );
        MatcherAssert.assertThat(
            "blank returns EMPTY",
            SearchQueryParser.parse("   "),
            new IsEqual<>(SearchQuery.EMPTY)
        );
    }

    @Test
    void unknownFieldTreatedAsFts() {
        final SearchQuery result = SearchQueryParser.parse("unknown:value");
        MatcherAssert.assertThat(
            "unknown field should go to ftsQuery",
            result.ftsQuery(),
            new IsEqual<>("unknown:value")
        );
        MatcherAssert.assertThat(
            "no filters expected",
            result.filters(),
            Matchers.empty()
        );
    }

    @Test
    void repoFieldUsesExactMatch() {
        final SearchQuery result = SearchQueryParser.parse("repo:pypi-proxy");
        MatcherAssert.assertThat(
            "repo field match type should be EXACT",
            result.filters().get(0).matchType(),
            new IsEqual<>(MatchType.EXACT)
        );
    }

    @Test
    void typeFieldUsesPrefixMatch() {
        final SearchQuery result = SearchQueryParser.parse("type:pypi");
        MatcherAssert.assertThat(
            "type field match type should be PREFIX",
            result.filters().get(0).matchType(),
            new IsEqual<>(MatchType.PREFIX)
        );
    }

    @Test
    void spaceAfterColonIsParsed() {
        final SearchQuery result = SearchQueryParser.parse("name: pydantic");
        MatcherAssert.assertThat(
            "space-after-colon: ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "space-after-colon: should produce 1 filter",
            result.filters(),
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "space-after-colon: field should be name",
            result.filters().get(0).field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "space-after-colon: value should be pydantic",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("pydantic"))
        );
    }

    @Test
    void spaceAfterColonWithMultipleFields() {
        final SearchQuery result = SearchQueryParser.parse(
            "name: pydantic AND version: 2.12"
        );
        MatcherAssert.assertThat(
            "should have 2 filters",
            result.filters(),
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "first filter field",
            result.filters().get(0).field(),
            new IsEqual<>("name")
        );
        MatcherAssert.assertThat(
            "second filter field",
            result.filters().get(1).field(),
            new IsEqual<>("version")
        );
    }

    @Test
    void versionPrefixVStripped() {
        final SearchQuery result = SearchQueryParser.parse("version:v1.0.0");
        MatcherAssert.assertThat(
            "v prefix should be stripped",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("1.0.0"))
        );
    }

    @Test
    void versionPrefixUpperVStripped() {
        final SearchQuery result = SearchQueryParser.parse("version:V2.3.4");
        MatcherAssert.assertThat(
            "V prefix should be stripped",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("2.3.4"))
        );
    }

    @Test
    void versionWithoutVPrefixUnchanged() {
        final SearchQuery result = SearchQueryParser.parse("version:2.12");
        MatcherAssert.assertThat(
            "version without v should be unchanged",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("2.12"))
        );
    }

    @Test
    void nameFieldVPrefixNotStripped() {
        final SearchQuery result = SearchQueryParser.parse("name:vscode");
        MatcherAssert.assertThat(
            "v prefix should NOT be stripped for name field",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("vscode"))
        );
    }

    @Test
    void spaceAfterColonOrGrouping() {
        final SearchQuery result = SearchQueryParser.parse(
            "name: pydantic AND (version: v2.12 OR version: v2.11)"
        );
        MatcherAssert.assertThat(
            "should have 2 filters",
            result.filters(),
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "version values should have v stripped and be OR'd",
            result.filters().get(1).values(),
            new IsEqual<>(List.of("2.12", "2.11"))
        );
    }

    @Test
    void fullComplexQuery() {
        final SearchQuery result = SearchQueryParser.parse(
            "name:pydantic AND (version:2.12 OR version:2.11) AND repo:pypi-proxy"
        );
        MatcherAssert.assertThat(
            "ftsQuery should be empty",
            result.ftsQuery(),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "should have 3 filters",
            result.filters(),
            Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "name filter values",
            result.filters().get(0).values(),
            new IsEqual<>(List.of("pydantic"))
        );
        MatcherAssert.assertThat(
            "version filter values",
            result.filters().get(1).values(),
            new IsEqual<>(List.of("2.12", "2.11"))
        );
        MatcherAssert.assertThat(
            "repo filter values",
            result.filters().get(2).values(),
            new IsEqual<>(List.of("pypi-proxy"))
        );
    }
}
