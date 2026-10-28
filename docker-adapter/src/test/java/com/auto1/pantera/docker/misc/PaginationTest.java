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

import javax.json.JsonArrayBuilder;
import javax.json.JsonString;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tests for {@link Pagination}: the page a request selects and the
 * {@code Link: rel="next"} it earns (WS4-docker.4) — a full page links to
 * the entry after its last one, a short page or an unbounded listing does not.
 */
final class PaginationTest {

    /**
     * Path the pages are served under.
     */
    private static final String PATH = "/v2/my-alpine/tags/list";

    @Test
    void linksNextPageWhenPageIsFull() {
        MatcherAssert.assertThat(
            new Pagination(null, 2).nextLink(PaginationTest.PATH, List.of("1", "2")),
            new IsEqual<>(Optional.of("</v2/my-alpine/tags/list?n=2&last=2>; rel=\"next\""))
        );
    }

    @Test
    void doesNotLinkWhenPageIsNotFull() {
        MatcherAssert.assertThat(
            new Pagination(null, 3).nextLink(PaginationTest.PATH, List.of("1", "2")),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void doesNotLinkWithoutPageSize() {
        MatcherAssert.assertThat(
            Pagination.empty().nextLink(PaginationTest.PATH, List.of("1", "2", "3")),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void doesNotLinkEmptyPage() {
        MatcherAssert.assertThat(
            new Pagination(null, 0).nextLink(PaginationTest.PATH, List.of()),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void encodesCursorInLink() {
        MatcherAssert.assertThat(
            new Pagination(null, 2).nextLink("/v2/_catalog", List.of("a", "team/b")),
            new IsEqual<>(Optional.of("</v2/_catalog?n=2&last=team%2Fb>; rel=\"next\""))
        );
    }

    @Test
    void cursorRoundTripsThroughPages() {
        final List<String> all = List.of("3", "1", "2", "4", "5");
        final Pagination first = new Pagination(null, 2);
        final List<String> one = PaginationTest.asList(first.apply(all.stream()));
        MatcherAssert.assertThat(
            "first page holds the first n sorted entries",
            one, new IsEqual<>(List.of("1", "2"))
        );
        final Pagination second = new Pagination(one.get(one.size() - 1), 2);
        final List<String> two = PaginationTest.asList(second.apply(all.stream()));
        MatcherAssert.assertThat(
            "following the cursor resumes exactly after the previous page",
            two, new IsEqual<>(List.of("3", "4"))
        );
        MatcherAssert.assertThat(
            "a full middle page links onwards",
            second.nextLink(PaginationTest.PATH, two).isPresent(), new IsEqual<>(true)
        );
        final Pagination third = new Pagination(two.get(two.size() - 1), 2);
        final List<String> three = PaginationTest.asList(third.apply(all.stream()));
        MatcherAssert.assertThat(
            "the last page holds the remainder",
            three, new IsEqual<>(List.of("5"))
        );
        MatcherAssert.assertThat(
            "a short last page does not link",
            third.nextLink(PaginationTest.PATH, three), new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void appliesCursorLimitOrderAndDistinct() {
        MatcherAssert.assertThat(
            PaginationTest.asList(
                new Pagination("1", 2).apply(Stream.of("3", "1", "2", "2", "4"))
            ),
            new IsEqual<>(List.of("2", "3"))
        );
    }

    private static List<String> asList(final JsonArrayBuilder page) {
        return page.build().getValuesAs(JsonString.class).stream()
            .map(JsonString::getString)
            .toList();
    }
}
