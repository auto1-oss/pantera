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
package com.auto1.pantera.docker.misc;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import javax.json.JsonString;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tests for {@link Pagination#page(Stream)} — the truncation/cursor detection that
 * drives the {@code Link: rel="next"} header on {@code tags/list} and {@code _catalog}
 * (WS4-docker.4).
 */
final class PaginationTest {

    @Test
    void reportsTruncatedAndCursorWhenMoreThanN() {
        final Pagination.Page page = new Pagination(null, 2)
            .page(Stream.of("3", "1", "2", "4", "5"));
        MatcherAssert.assertThat(
            "Page must be truncated when more entries exist beyond n",
            page.truncated(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Cursor must be the last value of the emitted page",
            page.cursor(), new IsEqual<>(Optional.of("2"))
        );
        MatcherAssert.assertThat(
            "Emitted page must contain exactly n values",
            asList(page), new IsEqual<>(List.of("1", "2"))
        );
    }

    @Test
    void notTruncatedWhenExactlyN() {
        final Pagination.Page page = new Pagination(null, 5)
            .page(Stream.of("3", "1", "2", "4", "5"));
        MatcherAssert.assertThat(
            "Page must not be truncated when the result count equals n exactly",
            page.truncated(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            page.cursor(), new IsEqual<>(Optional.of("5"))
        );
        MatcherAssert.assertThat(
            asList(page), new IsEqual<>(List.of("1", "2", "3", "4", "5"))
        );
    }

    @Test
    void notTruncatedWhenFewerThanN() {
        final Pagination.Page page = new Pagination(null, 10)
            .page(Stream.of("3", "1", "2"));
        MatcherAssert.assertThat(
            "Page must not be truncated when fewer entries exist than n",
            page.truncated(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            page.cursor(), new IsEqual<>(Optional.of("3"))
        );
        MatcherAssert.assertThat(
            asList(page), new IsEqual<>(List.of("1", "2", "3"))
        );
    }

    @Test
    void notTruncatedWhenNoLimitGiven() {
        final Pagination.Page page = Pagination.empty()
            .page(Stream.of("3", "1", "2", "4", "5"));
        MatcherAssert.assertThat(page.truncated(), new IsEqual<>(false));
        MatcherAssert.assertThat(
            asList(page), new IsEqual<>(List.of("1", "2", "3", "4", "5"))
        );
    }

    @Test
    void emptyResultHasNoCursorAndIsNotTruncated() {
        final Pagination.Page page = new Pagination(null, 2).page(Stream.empty());
        MatcherAssert.assertThat(page.truncated(), new IsEqual<>(false));
        MatcherAssert.assertThat(page.cursor(), new IsEqual<>(Optional.empty()));
    }

    @Test
    void cursorRoundTripsAsNextPageLastParameter() {
        final Pagination.Page first = new Pagination(null, 2)
            .page(Stream.of("3", "1", "2", "4", "5"));
        final Pagination.Page second = new Pagination(first.cursor().orElseThrow(), 2)
            .page(Stream.of("3", "1", "2", "4", "5"));
        MatcherAssert.assertThat(
            "Following the cursor must resume exactly after the previous page",
            asList(second), new IsEqual<>(List.of("3", "4"))
        );
        MatcherAssert.assertThat(second.truncated(), new IsEqual<>(true));
        final Pagination.Page third = new Pagination(second.cursor().orElseThrow(), 2)
            .page(Stream.of("3", "1", "2", "4", "5"));
        MatcherAssert.assertThat(asList(third), new IsEqual<>(List.of("5")));
        MatcherAssert.assertThat(
            "Last page must not be truncated", third.truncated(), new IsEqual<>(false)
        );
    }

    private static List<String> asList(final Pagination.Page page) {
        return page.json().build().getValuesAs(JsonString.class).stream()
            .map(JsonString::getString)
            .toList();
    }
}
