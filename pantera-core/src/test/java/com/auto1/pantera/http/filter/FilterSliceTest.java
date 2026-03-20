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
package com.auto1.pantera.http.filter;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link FilterSlice}.
 */
public class FilterSliceTest {
    /**
     * Request path.
     */
    private static final String PATH = "/mvnrepo/com/pantera/inner/0.1/inner-0.1.pom";

    @Test
    void trowsExceptionOnEmptyFiltersConfiguration() {
        Assertions.assertThrows(
            NullPointerException.class,
            () -> new FilterSlice(
                (line, headers, body) -> CompletableFuture.completedFuture(ResponseBuilder.ok().build()),
                FiltersTestUtil.yaml("filters:")
            )
        );
    }

    @Test
    void shouldAllow() {
        final FilterSlice slice = new FilterSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            FiltersTestUtil.yaml(
                String.join(
                    System.lineSeparator(),
                    "filters:",
                    "  include:",
                    "    glob:",
                    "      - filter: **/*",
                    "  exclude:"
                )
            )
        );
        Assertions.assertEquals(
            RsStatus.OK,
            slice.response(
                FiltersTestUtil.get(FilterSliceTest.PATH),
                Headers.EMPTY,
                Content.EMPTY
            ).join().status()
        );
    }

    @Test
    void shouldForbidden() {
        Response res = new FilterSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            FiltersTestUtil.yaml(
                String.join(
                    System.lineSeparator(),
                    "filters:",
                    "  include:",
                    "  exclude:"
                )
            )
        ).response(FiltersTestUtil.get(FilterSliceTest.PATH), Headers.EMPTY, Content.EMPTY)
            .join();
        MatcherAssert.assertThat(
            res.status(),
            Matchers.is(RsStatus.FORBIDDEN)
        );
    }
}
