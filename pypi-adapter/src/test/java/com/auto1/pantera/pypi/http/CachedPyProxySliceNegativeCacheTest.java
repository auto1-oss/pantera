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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WS5.1 regression coverage: a cooldown-origin 404 on a PyPI metadata
 * (version-less) surface must never be written to the negative cache.
 *
 * <p>Proves the fix is already structurally in place: {@link
 * NegativeCacheKey#fromPath(String, String, String)} parses a {@code
 * /simple/&lt;pkg&gt;/} path (no {@code .whl}/{@code .tar.gz} suffix) to
 * an EMPTY {@code artifactVersion}, and {@code NegativeCache#cacheNotFound}
 * refuses to write any key whose version is empty (the 2.2.3 group
 * negative-cache fix, commit {@code 7cc43e0ab}). {@link
 * com.auto1.pantera.pypi.cooldown.PypiSimpleHandler#allBlockedResponse}
 * already stamps the 404 with {@code X-Pantera-Cooldown: all-blocked};
 * this test proves the two mechanisms compose correctly end-to-end
 * through {@link CachedPyProxySlice}, so a fully-blocked package becomes
 * installable again on the very next request once the block lifts —
 * not after the negative-cache TTL.
 *
 * @since 2.3.0
 */
final class CachedPyProxySliceNegativeCacheTest {

    @Test
    @DisplayName(
        "cooldown all-blocked 404 on /simple/<pkg>/ is never written to the "
            + "negative cache and every request re-hits the origin (WS5.1)"
    )
    void cooldownAllBlocked404IsNeverNegativeCached() throws Exception {
        final String pkg = "ws51-" + UUID.randomUUID();
        final String path = "/simple/" + pkg + "/";
        final Storage storage = new InMemoryStorage();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice origin = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound()
                    .header("X-Pantera-Cooldown", "all-blocked")
                    .textBody("All versions of '" + pkg + "' are under cooldown; "
                        + "no versions available.")
                    .build()
            );
        };
        final String repoName = "pypi-proxy-negcache-test";
        @SuppressWarnings("deprecation")
        final CachedPyProxySlice slice = new CachedPyProxySlice(
            origin,
            Optional.of(storage),
            Duration.ofHours(1),
            false,
            repoName,
            "https://upstream.example/pypi",
            "pypi"
        );

        final Response first = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "cooldown all-blocked response is served as 404",
            first.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        first.body().asBytesFuture().join();

        final NegativeCacheKey key = NegativeCacheKey.fromPath(repoName, "pypi", path);
        MatcherAssert.assertThat(
            "a cooldown-origin 404 on a metadata (version-less) path must never be "
                + "written to the negative cache — caching it would keep the package "
                + "404-ing until the negative-cache TTL instead of clearing the moment "
                + "the block lifts",
            NegativeCacheRegistry.instance().sharedCache().isKnown404(key),
            new IsEqual<>(false)
        );

        // Second call must reach the origin again — proves the negative-cache
        // short-circuit at the top of CachedPyProxySlice.response() never
        // engaged for this path (a hit there would return a synthetic 404
        // without ever calling upstream again).
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
        second.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "the second request must re-hit the origin, proving no negative-cache "
                + "short-circuit engaged for this cooldown-origin 404",
            upstreamCalls.get(),
            new IsEqual<>(2)
        );
    }
}
