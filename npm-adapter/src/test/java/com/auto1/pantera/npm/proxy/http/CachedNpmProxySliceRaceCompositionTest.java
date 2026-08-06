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
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * WS8 Bug B5, live-server regression: {@code NpmProxyAdapter} wires every
 * {@code npm-proxy} repository's {@code response()} as exactly {@code new
 * RaceSlice(<one CachedNpmProxySlice per configured remote>)} -- see {@code
 * NpmProxyAdapter} lines ~81-154. Even a single-remote proxy is still raced
 * through {@link RaceSlice}: its constructor accepts a target list of any
 * size, including one.
 *
 * <p>{@link CachedNpmProxySliceTest} drives {@code CachedNpmProxySlice}
 * directly and passed both before and after the fix, because {@code
 * CachedNpmProxySlice} itself already built the honest 404 body correctly
 * (commit {@code 6618f8b05}). The live defect was one layer up: {@code
 * RaceSlice}'s all-targets-404 terminal drained every target's body
 * (including the honest one) via {@code res.body().asBytesFuture()} and then,
 * on walk exhaustion, unconditionally completed with a bare {@code
 * ResponseBuilder.notFound().build()} -- discarding the body that had just
 * been read into memory one line above. A test that never composes {@link
 * RaceSlice} around {@code CachedNpmProxySlice} cannot see this: exactly what
 * happened for one full round of unit-test-only verification.
 *
 * <p>This test reproduces the real production composition -- {@link
 * RaceSlice} wrapping actual {@link CachedNpmProxySlice} instances -- with
 * only the network-facing "origin" (normally {@code NpmProxySlice} talking to
 * a real upstream) replaced by an in-memory stub, since no Docker/network/DB
 * is allowed in a {@code *Test.java}. Reaching the full wiring, including the
 * real HTTP client and {@code RepositorySlices}, would need an itcase against
 * a running stack (see {@code test_images/}) or the live-server confirmation
 * this fix was additionally verified against.
 */
final class CachedNpmProxySliceRaceCompositionTest {

    @Test
    void singleRemoteRaceSlicePreservesHonestNotFoundBody() throws Exception {
        final Slice origin = (line, headers, content) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Slice liveComposition = new RaceSlice(
            new CachedNpmProxySlice(
                origin, Optional.empty(), "race-honest-404-repo",
                "https://registry.npmjs.org", "npm"
            )
        );
        final Response response = liveComposition.response(
            new RequestLine(RqMethod.GET, "/pnpm/999.999.999"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "a single-remote npm-proxy request is still raced through RaceSlice in production",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "RaceSlice must forward CachedNpmProxySlice's honest body, not manufacture an empty one",
            response.body().asString(),
            new IsEqual<>("{\"error\":\"version not found: 999.999.999\",\"package\":\"pnpm\"}")
        );
    }

    @Test
    void multiRemoteRaceSlicePreservesHonestNotFoundBody() throws Exception {
        // Mirrors NpmProxyAdapter exactly: every remote of the SAME npm-proxy
        // repository shares one CachedNpmProxySlice repoName (cfg.name()) and
        // differs only in upstreamUrl -- so both targets share one negative
        // cache namespace too, same as production.
        final Slice originA = (line, headers, content) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Slice originB = (line, headers, content) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Slice liveComposition = new RaceSlice(
            new CachedNpmProxySlice(
                originA, Optional.empty(), "race-honest-404-multi-repo",
                "https://registry-a.example", "npm"
            ),
            new CachedNpmProxySlice(
                originB, Optional.empty(), "race-honest-404-multi-repo",
                "https://registry-b.example", "npm"
            )
        );
        final Response response = liveComposition.response(
            new RequestLine(RqMethod.GET, "/pnpm/999.999.999"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat(
            "with multiple proxy remotes racing, the group must still surface an honest "
                + "body once every remote 404s",
            response.body().asString(),
            new IsEqual<>("{\"error\":\"version not found: 999.999.999\",\"package\":\"pnpm\"}")
        );
    }
}
