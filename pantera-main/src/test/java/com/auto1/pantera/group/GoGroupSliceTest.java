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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.BaseCachedProxySlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
            "go-group",
            List.of(
                new MemberSlice("local", text("v0.9.21-qa\n"), false),
                new MemberSlice("proxy", text("v0.1.0\nv0.9.1\nv0.9.21-qa\n"), true)
            )
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
            "go-group",
            List.of(
                new MemberSlice("local", counting(new AtomicInteger()), false),
                new MemberSlice("proxy", counting(new AtomicInteger()), true)
            )
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
            "go-group",
            List.of(new MemberSlice("local", text("unused"), false))
        );
        body(
            slice.response(
                new RequestLine("GET", "/github.com/pkg/errors/@v/v0.9.1.info"),
                Headers.EMPTY, Content.EMPTY
            ).join()
        );
        MatcherAssert.assertThat(delegated.get(), new IsEqual<>(1));
    }

    @Test
    void openCircuitMemberGetsOnlyACacheOnlyProbe() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(0.5, 1, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        registry.recordFailure("proxy");
        final List<String> direct = new CopyOnWriteArrayList<>();
        final Slice proxy = (line, headers, body) -> {
            if (headers.values(BaseCachedProxySlice.CACHE_ONLY_HEADER).isEmpty()) {
                direct.add(line.uri().getPath());
                return CompletableFuture.completedFuture(ResponseBuilder.ok().textBody("v9.9.9\n").build());
            }
            return CompletableFuture.completedFuture(ResponseBuilder.ok().textBody("v0.9.1\n").build());
        };
        final GoGroupSlice slice = new GoGroupSlice(
            counting(new AtomicInteger()),
            "go-group",
            List.of(
                new MemberSlice("local", text("v0.9.21-qa\n"), registry, false),
                new MemberSlice("proxy", proxy, registry, true)
            )
        );
        MatcherAssert.assertThat(
            "the open member contributes only its warm-cache list",
            body(slice.response(new RequestLine("GET", LIST), Headers.EMPTY, Content.EMPTY).join()),
            new IsEqual<>("v0.9.21-qa\nv0.9.1\n")
        );
        MatcherAssert.assertThat(
            "the open-circuit member is not called with a normal request",
            direct.isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void memberFailureIsRecordedOnceAndNotRetriedThroughTheWalk() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(1.0, 2, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger delegated = new AtomicInteger();
        final Slice failing = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE).build()
            );
        };
        final GoGroupSlice slice = new GoGroupSlice(
            counting(delegated),
            "go-group",
            List.of(new MemberSlice("proxy", failing, registry, true))
        );
        final Response resp = slice.response(
            new RequestLine("GET", LIST), Headers.EMPTY, Content.EMPTY
        ).join();
        body(resp);
        MatcherAssert.assertThat(
            "a failing member is a server error",
            resp.status().serverError(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the failing member is asked once",
            calls.get() + delegated.get(),
            new IsEqual<>(1)
        );
        registry.recordFailure("proxy");
        MatcherAssert.assertThat(
            "the failure was recorded on the member's shared breaker",
            registry.isBlocked("proxy"),
            new IsEqual<>(true)
        );
    }

    @Test
    void circuitOpenMarkerIsASkipWithoutConviction() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(0.5, 1, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        final Slice marked = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                .header(UpstreamCircuitOpenException.HEADER, "true")
                .header("Retry-After", "30")
                .build()
        );
        final GoGroupSlice slice = new GoGroupSlice(
            counting(new AtomicInteger()),
            "go-group",
            List.of(new MemberSlice("proxy", marked, registry, true))
        );
        final Response resp = slice.response(
            new RequestLine("GET", LIST), Headers.EMPTY, Content.EMPTY
        ).join();
        body(resp);
        MatcherAssert.assertThat(
            "every member unavailable answers 503, never 404",
            resp.status(),
            new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the unavailable answer carries Retry-After",
            resp.headers().values("Retry-After").isEmpty(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a marked fast-fail does not convict the member",
            registry.isBlocked("proxy"),
            new IsEqual<>(false)
        );
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
