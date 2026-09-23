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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GoGroupSlice}.
 *
 * @since 2.2.9
 */
final class GoGroupSliceTest {

    private static final String LIST = "/github.com/pkg/errors/@v/list";

    @Test
    void mergesVersionListsOfAllMembers() {
        final AtomicInteger delegated = new AtomicInteger();
        final GoGroupSlice slice = new GoGroupSlice(
            counting(delegated),
            (name, port, depth) -> Map.of(
                "local", text("v0.9.21-qa\n"),
                "proxy", text("v0.1.0\nv0.9.1\nv0.9.21-qa\n")
            ).get(name.string()),
            List.of("local", "proxy"),
            0
        );
        MatcherAssert.assertThat(
            "the list is the de-duplicated union of every member's list",
            body(slice.response(new RequestLine("GET", LIST), Headers.EMPTY, Content.EMPTY).join()),
            new IsEqual<>("v0.9.21-qa\nv0.1.0\nv0.9.1\n")
        );
        MatcherAssert.assertThat(
            "the merged list does not go through the first-wins walk",
            delegated.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void fallsBackToTheWalkWhenNoMemberHasAList() {
        final AtomicInteger delegated = new AtomicInteger();
        final GoGroupSlice slice = new GoGroupSlice(
            counting(delegated),
            (name, port, depth) -> (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.notFound().build()),
            List.of("local", "proxy"),
            0
        );
        final Response resp = slice.response(
            new RequestLine("GET", LIST), Headers.EMPTY, Content.EMPTY
        ).join();
        body(resp);
        MatcherAssert.assertThat(delegated.get(), new IsEqual<>(1));
    }

    @Test
    void otherPathsGoToTheWalk() {
        final AtomicInteger delegated = new AtomicInteger();
        final GoGroupSlice slice = new GoGroupSlice(
            counting(delegated),
            (name, port, depth) -> text("unused"),
            List.of("local"),
            0
        );
        body(
            slice.response(
                new RequestLine("GET", "/github.com/pkg/errors/@v/v0.9.1.info"),
                Headers.EMPTY, Content.EMPTY
            ).join()
        );
        MatcherAssert.assertThat(delegated.get(), new IsEqual<>(1));
    }

    private static Slice text(final String body) {
        return (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().textBody(body).build()
        );
    }

    private static Slice counting(final AtomicInteger calls) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
    }

    private static String body(final Response resp) {
        return new String(resp.body().asBytesFuture().join(), StandardCharsets.UTF_8);
    }
}
