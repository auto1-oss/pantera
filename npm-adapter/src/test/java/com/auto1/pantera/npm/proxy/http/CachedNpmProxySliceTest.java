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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression tests for {@link CachedNpmProxySlice}'s dedup wrapper.
 *
 * <p>The origin slice is where per-request side effects live — the
 * {@code artifact_resolution} / {@code artifact_access} audit records and
 * the phase metrics. The pre-fix wrapper ran the origin TWICE per client
 * request on the success path (a signal probe whose response was discarded
 * unconsumed, then a re-fetch that produced the actual client response), so
 * every audit record was emitted twice with the same trace.id milliseconds
 * apart. The dedup leader must serve the response from its single origin
 * traversal; only coalesced followers re-fetch (which is THEIR single
 * traversal).</p>
 */
final class CachedNpmProxySliceTest {

    @Test
    void singleRequestTraversesOriginExactlyOnce() throws Exception {
        final byte[] body = "packument-body".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger invocations = new AtomicInteger();
        final Slice origin = (line, headers, content) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(body).build()
            );
        };
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "audit-dedup-repo", "upstream", "npm"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/audit-dedup-pkg-once"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "response served with origin's body",
            response.body().asBytesFuture().get(),
            new IsEqual<>(body)
        );
        MatcherAssert.assertThat(
            "one client request = one origin traversal (one set of audit records)",
            invocations.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void notModifiedRevalidationTraversesOriginExactlyOnce() throws Exception {
        final AtomicInteger invocations = new AtomicInteger();
        final Slice origin = (line, headers, content) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                    .header("ETag", "etag-value")
                    .build()
            );
        };
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "audit-dedup-repo", "upstream", "npm"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/audit-dedup-pkg-304"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "leader's own 304 is served to the client",
            response.status(),
            new IsEqual<>(RsStatus.NOT_MODIFIED)
        );
        MatcherAssert.assertThat(
            "an ETag revalidation is one origin traversal, not a probe plus re-fetch",
            invocations.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void upstreamNotFoundStillMapsToSyntheticNotFound() throws Exception {
        final AtomicInteger invocations = new AtomicInteger();
        final Slice origin = (line, headers, content) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        };
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "audit-dedup-repo", "upstream", "npm"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/audit-dedup-pkg-404"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "404 keeps the synthetic mapping (RaceSlice fallback contract)",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the 404 probe is a single origin traversal",
            invocations.get(),
            new IsEqual<>(1)
        );
    }

    /**
     * WS8 Bug B2 (the more important half): a probe request must never be
     * able to poison a real one. The origin here simulates the exact
     * routing-gap shape the bug report described -- a path this repository
     * does not (yet) serve on {@code HEAD} 404s -- independent of whether
     * {@code NpmProxySlice}'s own route table has since been fixed to route
     * HEAD correctly; this proves the wrapper's own defense holds even if
     * some future route still has the same gap.
     */
    @Test
    void headNotFoundDoesNotPoisonSubsequentGet() throws Exception {
        final byte[] tarball = "tarball-bytes".getBytes(StandardCharsets.UTF_8);
        final Slice origin = (line, headers, content) -> CompletableFuture.completedFuture(
            line.method() == RqMethod.HEAD
                ? ResponseBuilder.notFound().build()
                : ResponseBuilder.ok().body(tarball).build()
        );
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "head-poison-repo", "upstream", "npm"
        );
        final String path = "/head-poison-pkg/-/head-poison-pkg-1.0.0.tgz";
        final Response headResponse = slice.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "the probe itself still reports its own 404 to its caller",
            headResponse.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        final Response getResponse = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "a HEAD probe must never poison the shared negative cache for a "
                + "subsequent GET of the exact same path",
            getResponse.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the GET must still receive the real artifact bytes",
            getResponse.body().asBytesFuture().get(),
            new IsEqual<>(tarball)
        );
    }

    /**
     * WS8 Bug B5: proxy/group 404s for a single-version-shaped path must
     * carry the same honest {@code {"error":..., "package":...}} body local
     * mode's {@code SingleVersionSlice#notFound} builds, not an empty one.
     */
    @Test
    void notFoundCarriesHonestBodyForSingleVersionPath() throws Exception {
        final Slice origin = (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.notFound().build()
        );
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "honest-404-repo", "upstream", "npm"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/honest-404-pkg/9.9.9"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat(
            "a cold-miss 404 for /<pkg>/<version> must name both the package "
                + "and the unresolved version, matching local mode's shape",
            response.body().asString(),
            new IsEqual<>("{\"error\":\"version not found: 9.9.9\",\"package\":\"honest-404-pkg\"}")
        );
    }

    /**
     * WS8 Bug B5: the honest body must also survive a warm negative-cache
     * hit (the second identical request), not just the cold miss that
     * populated the cache -- the shape must be consistent regardless of
     * which code path answered it.
     */
    @Test
    void negativeCacheHitAlsoCarriesHonestBody() throws Exception {
        final Slice origin = (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.notFound().build()
        );
        final CachedNpmProxySlice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "honest-404-warm-repo", "upstream", "npm"
        );
        final RequestLine request = new RequestLine(RqMethod.GET, "/honest-404-warm-pkg/9.9.9");
        slice.response(request, Headers.EMPTY, Content.EMPTY).get();
        final Response warm = slice.response(request, Headers.EMPTY, Content.EMPTY).get();
        MatcherAssert.assertThat(warm.status(), new IsEqual<>(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat(
            "a warm negative-cache hit must carry the same honest body as the "
                + "cold miss that populated it",
            warm.body().asString(),
            new IsEqual<>(
                "{\"error\":\"version not found: 9.9.9\",\"package\":\"honest-404-warm-pkg\"}"
            )
        );
    }
}
