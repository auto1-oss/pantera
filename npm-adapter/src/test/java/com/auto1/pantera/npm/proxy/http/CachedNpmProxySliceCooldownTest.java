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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A cooldown 403 produced by the npm proxy itself must reach the client
 * verbatim (status, reason body, Retry-After) instead of being laundered into
 * the non-authoritative 404 reserved for genuine upstream refusals.
 *
 * <p>Each test uses its own path: the negative cache behind
 * {@link CachedNpmProxySlice} is a process-wide singleton.</p>
 *
 * @since 2.2.9
 */
final class CachedNpmProxySliceCooldownTest {

    private static final String BODY =
        "{\"error\":\"version in cooldown\",\"blocked_until\":\"2026-09-29T15:44:39Z\"}";

    @Test
    void cooldownVerdictReachesTheClientVerbatim() {
        final Response response = CachedNpmProxySliceCooldownTest.slice(new AtomicInteger())
            .response(CachedNpmProxySliceCooldownTest.get("/cd-verbatim/-/cd-verbatim-1.0.0.tgz"),
                Headers.EMPTY, Content.EMPTY)
            .join();
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
            new String(response.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>(BODY)
        );
        MatcherAssert.assertThat(
            "not marked as a laundered upstream miss",
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no upstream-status annotation",
            response.headers().values("X-Pantera-Upstream-Status").isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void cooldownVerdictIsNotNegativeCached() {
        // First answer: cooldown 403. After an unblock the origin serves 200 —
        // the very next request must see it, not a cached miss.
        final AtomicInteger calls = new AtomicInteger();
        final Slice origin = (line, headers, content) -> CompletableFuture.completedFuture(
            calls.incrementAndGet() == 1
                ? CachedNpmProxySliceCooldownTest.cooldown()
                : ResponseBuilder.ok().textBody("tarball").build()
        );
        final Slice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "cd-repo", "upstream", "npm-proxy"
        );
        final RequestLine line = CachedNpmProxySliceCooldownTest.get("/cd-unblock/-/cd-unblock-1.0.0.tgz");
        MatcherAssert.assertThat(
            "blocked first",
            slice.response(line, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "served right after the unblock",
            slice.response(line, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat("origin consulted both times", calls.get(), new IsEqual<>(2));
    }

    @Test
    void allCoalescedWaitersSeeTheCooldownVerdict() {
        final Slice slice = CachedNpmProxySliceCooldownTest.slice(new AtomicInteger());
        final RequestLine line = CachedNpmProxySliceCooldownTest.get("/cd-burst/-/cd-burst-1.0.0.tgz");
        final CompletableFuture<Response> first = slice.response(line, Headers.EMPTY, Content.EMPTY);
        final CompletableFuture<Response> second = slice.response(line, Headers.EMPTY, Content.EMPTY);
        MatcherAssert.assertThat(
            "leader sees 403", first.join().status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "follower sees 403 too", second.join().status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
    }

    @Test
    void raceForwardsTheCooldownVerdictWhenNoRemoteServesIt() {
        final Slice race = new RaceSlice(List.of(
            CachedNpmProxySliceCooldownTest.slice(new AtomicInteger()),
            CachedNpmProxySliceCooldownTest.slice(new AtomicInteger())
        ));
        final Response response = race.response(
            CachedNpmProxySliceCooldownTest.get("/cd-race/-/cd-race-1.0.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "race answers 403", response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "race keeps the cooldown body",
            new String(response.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>(BODY)
        );
    }

    @Test
    void raceStillPrefersARemoteThatServesTheArtifact() {
        final Slice serving = new CachedNpmProxySlice(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("tarball").build()
            ),
            Optional.empty(), "cd-repo", "mirror", "npm-proxy"
        );
        final Response response = new RaceSlice(List.of(
            CachedNpmProxySliceCooldownTest.slice(new AtomicInteger()), serving
        )).response(
            CachedNpmProxySliceCooldownTest.get("/cd-race-ok/-/cd-race-ok-1.0.0.tgz"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
    }

    @Test
    void upstreamForbiddenWithoutCooldownMarkerIsStillLaundered() {
        final Slice slice = new CachedNpmProxySlice(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.forbidden().build()
            ),
            Optional.empty(), "cd-repo", "upstream", "npm-proxy"
        );
        final Response response = slice.response(
            CachedNpmProxySliceCooldownTest.get("/cd-upstream-403"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "upstream refusal becomes the non-authoritative 404",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and is marked so a group does not negative-cache it",
            response.headers().values(NegativeCache.SKIP_HEADER), new IsEqual<>(List.of("true"))
        );
    }

    private static Slice slice(final AtomicInteger calls) {
        return new CachedNpmProxySlice(
            (line, headers, content) -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(CachedNpmProxySliceCooldownTest.cooldown());
            },
            Optional.empty(), "cd-repo", "upstream", "npm-proxy"
        );
    }

    private static Response cooldown() {
        return ResponseBuilder.forbidden()
            .header("Retry-After", "3600")
            .header("X-Pantera-Cooldown", "blocked")
            .jsonBody(BODY)
            .build();
    }

    private static RequestLine get(final String path) {
        return new RequestLine(RqMethod.GET, path);
    }
}
