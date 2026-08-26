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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Regression tests for {@link CachedNpmProxySlice} naming the observed
 * upstream status on a laundered (non-authoritative) 404 -- see
 * {@link UpstreamOutcome}.
 *
 * <p>{@link RsStatus} is a closed enum with no constant for 451 (Unavailable
 * For Legal Reasons) -- the very status motivating this feature -- so these
 * tests stand a stub origin up on {@code 403 Forbidden} instead, an existing
 * enum member that {@code CachedNpmProxySlice#doFetch} already documents as
 * one of the non-404 4xx codes routed through this same path. The mechanism
 * under test -- carrying whatever status was observed through to the client
 * without changing the response status -- is identical regardless of which
 * non-404 code produced it.</p>
 *
 * <p>Each test uses its own request path even where the source brief for
 * this change reused {@code /pkg} across tests: the negative-cache registry
 * backing {@link CachedNpmProxySlice} is a process-wide singleton, and the
 * genuine-404 test below deliberately populates it, so sharing a path with
 * the non-authoritative-miss test would make that test's outcome depend on
 * JUnit's (unspecified) method order.</p>
 */
final class CachedNpmProxySliceOutcomeTest {

    @Test
    void marksTheObservedUpstreamStatusOnANonAuthoritativeMiss() {
        // Origin answers 403; the slice must answer 404 (routing contract)
        // while naming what it actually saw.
        final Response response = this.sliceOverOriginReturning(403).response(
            new RequestLine(RqMethod.GET, "/outcome-pkg-unverified"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "status stays 404 so the race and group walk still fall through",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the observed upstream status is preserved for logs and clients",
            response.headers().values("X-Pantera-Upstream-Status"),
            new IsEqual<>(List.of("403"))
        );
        MatcherAssert.assertThat(
            "the non-authoritative marker is still present",
            response.headers().values(NegativeCache.SKIP_HEADER),
            new IsEqual<>(List.of("true"))
        );
    }

    @Test
    void doesNotMarkAGenuineUpstreamNotFound() {
        final Response response = this.sliceOverOriginReturning(404).response(
            new RequestLine(RqMethod.GET, "/outcome-pkg-genuine-404"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values("X-Pantera-Upstream-Status").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void allCoalescedWaitersSeeTheSameObservation() {
        // Two concurrent requests for the same key share one upstream fetch;
        // both must carry the same observed status, not just the leader.
        final Slice slice = this.sliceOverOriginReturning(403);
        final CompletableFuture<Response> first = slice.response(
            new RequestLine(RqMethod.GET, "/outcome-pkg-same"), Headers.EMPTY, Content.EMPTY
        );
        final CompletableFuture<Response> second = slice.response(
            new RequestLine(RqMethod.GET, "/outcome-pkg-same"), Headers.EMPTY, Content.EMPTY
        );
        MatcherAssert.assertThat(
            second.join().headers().values("X-Pantera-Upstream-Status"),
            new IsEqual<>(first.join().headers().values("X-Pantera-Upstream-Status"))
        );
    }

    /**
     * Build a {@link CachedNpmProxySlice} whose origin unconditionally
     * answers with the given status.
     *
     * @param status Status the stub origin returns; must be a status
     *  {@link RsStatus} defines (e.g. 403 or 404)
     * @return A slice wrapping that stub origin
     */
    private Slice sliceOverOriginReturning(final int status) {
        final Slice origin = (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.byCode(status)).build()
        );
        return new CachedNpmProxySlice(
            origin, Optional.empty(), "outcome-repo", "upstream", "npm"
        );
    }
}
