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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cooldown verdicts produced by the PyPI proxy (the all-versions-blocked
 * 404 of {@code /simple/} and {@code /pypi/<pkg>/json}, the 403 for a blocked
 * distribution file) must reach the client verbatim and must never be
 * negative-cached, so an unblock is visible on the very next request.
 *
 * <p>The all-blocked 404s are kept out of the negative cache by
 * {@code NegativeCache}'s version-less-key guard; those tests pin that
 * contract for the PyPI metadata paths.</p>
 *
 * <p>Each test uses its own package name: the negative cache behind
 * {@link CachedPyProxySlice} is a process-wide singleton.</p>
 *
 * @since 2.2.9
 */
final class CachedPyProxySliceCooldownTest {

    /**
     * Body of the blocked-file 403.
     */
    private static final String BLOCKED =
        "Version blocked by cooldown policy. Blocked until: 2026-09-29T15:44:39Z";

    @Test
    void allBlockedSimpleIndexIsRelayedAndNotNegativeCached() {
        CachedPyProxySliceCooldownTest.assertAllBlockedNotCached("/simple/cd-simple-pkg/");
    }

    @Test
    void allBlockedJsonApiIsRelayedAndNotNegativeCached() {
        CachedPyProxySliceCooldownTest.assertAllBlockedNotCached("/pypi/cd-json-pkg/json");
    }

    @Test
    void blockedWheelVerdictReachesTheClientVerbatim() {
        CachedPyProxySliceCooldownTest.assertBlockedFileVerbatim(
            "/cd-wheel/cd_wheel-1.0.0-py3-none-any.whl"
        );
    }

    @Test
    void blockedSdistVerdictReachesTheClientVerbatim() {
        CachedPyProxySliceCooldownTest.assertBlockedFileVerbatim("/cd-sdist/cd-sdist-1.0.0.tar.gz");
    }

    @Test
    void blockedEggVerdictReachesTheClientVerbatim() {
        CachedPyProxySliceCooldownTest.assertBlockedFileVerbatim("/cd-egg/cd_egg-1.0.0-py3.11.egg");
    }

    @Test
    void blockedWheelIsServedRightAfterUnblock() {
        final AtomicInteger calls = new AtomicInteger();
        final String path = "/cd-unblock/cd_unblock-1.0.0-py3-none-any.whl";
        final Slice slice = CachedPyProxySliceCooldownTest.slice(
            (line, headers, content) -> {
                if (!line.uri().getPath().equals(path)) {
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
                return CompletableFuture.completedFuture(
                    calls.incrementAndGet() == 1
                        ? CachedPyProxySliceCooldownTest.blocked()
                        : ResponseBuilder.ok().textBody("wheel").build()
                );
            },
            true
        );
        final RequestLine line = CachedPyProxySliceCooldownTest.get(path);
        MatcherAssert.assertThat(
            "blocked first",
            slice.response(line, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        final Response second = slice.response(line, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "served right after the unblock", second.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "wheel bytes served",
            new String(second.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>("wheel")
        );
        MatcherAssert.assertThat("origin consulted both times", calls.get(), new IsEqual<>(2));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void coalescedWaiterSeesTheCooldownVerdict() {
        final String path = "/cd-burst/cd_burst-1.0.0-py3-none-any.whl";
        final CompletableFuture<Response> parked = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();
        final Slice slice = CachedPyProxySliceCooldownTest.slice(
            (line, headers, content) -> {
                if (!line.uri().getPath().equals(path)) {
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
                if (calls.incrementAndGet() == 1) {
                    return parked;
                }
                return CompletableFuture.completedFuture(CachedPyProxySliceCooldownTest.blocked());
            },
            true
        );
        final RequestLine line = CachedPyProxySliceCooldownTest.get(path);
        final CompletableFuture<Response> leader = slice.response(line, Headers.EMPTY, Content.EMPTY);
        final CompletableFuture<Response> follower =
            slice.response(line, Headers.EMPTY, Content.EMPTY);
        parked.complete(CachedPyProxySliceCooldownTest.blocked());
        MatcherAssert.assertThat(
            "leader sees 403", leader.join().status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        final Response waiter = follower.join();
        MatcherAssert.assertThat(
            "follower sees 403 too", waiter.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "follower keeps the cooldown marker",
            waiter.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("blocked"))
        );
    }

    @Test
    void genuineUpstreamRefusalOfAWheelIsStillA404() {
        final Slice slice = CachedPyProxySliceCooldownTest.slice(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.forbidden().textBody("upstream says no").build()
            ),
            true
        );
        final Response response = slice.response(
            CachedPyProxySliceCooldownTest.get("/cd-upstream/cd_upstream-1.0.0-py3-none-any.whl"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.NOT_FOUND));
    }

    private static void assertAllBlockedNotCached(final String path) {
        final AtomicInteger calls = new AtomicInteger();
        final Slice slice = CachedPyProxySliceCooldownTest.slice(
            CachedPyProxySliceCooldownTest.unblockAfterFirst(
                calls, CachedPyProxySliceCooldownTest.allBlocked()
            ),
            true
        );
        final RequestLine line = CachedPyProxySliceCooldownTest.get(path);
        final Response first = slice.response(line, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "all-blocked answer is a 404", first.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "cooldown marker kept",
            first.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("all-blocked"))
        );
        MatcherAssert.assertThat(
            "reason body kept",
            new String(first.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("all blocked")
        );
        MatcherAssert.assertThat(
            "served right after the unblock",
            slice.response(line, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat("origin consulted both times", calls.get(), new IsEqual<>(2));
    }

    private static void assertBlockedFileVerbatim(final String path) {
        final Slice slice = CachedPyProxySliceCooldownTest.slice(
            (line, headers, content) -> CompletableFuture.completedFuture(
                line.uri().getPath().equals(path)
                    ? CachedPyProxySliceCooldownTest.blocked()
                    : ResponseBuilder.notFound().build()
            ),
            true
        );
        final Response response = slice.response(
            CachedPyProxySliceCooldownTest.get(path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "status stays 403", response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "cooldown marker kept",
            response.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("blocked"))
        );
        MatcherAssert.assertThat(
            "Retry-After kept",
            response.headers().values("Retry-After"), new IsEqual<>(List.of("3600"))
        );
        MatcherAssert.assertThat(
            "reason body kept",
            new String(response.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>(BLOCKED)
        );
    }

    private static Slice unblockAfterFirst(final AtomicInteger calls, final Response verdict) {
        return (line, headers, content) -> CompletableFuture.completedFuture(
            calls.incrementAndGet() == 1
                ? verdict
                : ResponseBuilder.ok().textBody("<html></html>").build()
        );
    }

    @SuppressWarnings("deprecation")
    private static Slice slice(final Slice origin, final boolean storage) {
        return new CachedPyProxySlice(
            origin,
            storage ? Optional.of(new InMemoryStorage()) : Optional.empty(),
            Duration.ofHours(1),
            true,
            "cd-pypi-proxy",
            "https://upstream.example/pypi",
            "pypi"
        );
    }

    private static Response allBlocked() {
        return ResponseBuilder.notFound()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody("all blocked")
            .build();
    }

    private static Response blocked() {
        return ResponseBuilder.forbidden()
            .header("Retry-After", "3600")
            .header("X-Pantera-Cooldown", "blocked")
            .textBody(BLOCKED)
            .build();
    }

    private static RequestLine get(final String path) {
        return new RequestLine(RqMethod.GET, path);
    }
}
