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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the cache-only probe contract on {@link BaseCachedProxySlice}
 * (breaker-cascade fix F5): a request carrying both the internal-routing
 * header and {@link BaseCachedProxySlice#CACHE_ONLY_HEADER} is served
 * exclusively from the warm cache — a hit returns the cached bytes, a
 * miss returns 404, and the upstream is never contacted in either case.
 * Without the internal-routing header the marker is ignored (an external
 * client cannot force cache-only mode).
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceCacheOnlyTest {

    /** Artifact path used by every test. */
    private static final String ARTIFACT_PATH =
        "/com/example/foo/1.0/foo-1.0.jar";

    /** Matching storage key ({@code KeyFromPath} strips the slash). */
    private static final Key ARTIFACT_KEY =
        new Key.From("com/example/foo/1.0/foo-1.0.jar");

    /** Bytes pre-seeded into the warm cache. */
    private static final byte[] CACHED_BYTES =
        "cached-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(10)
    @DisplayName("cache-only + warm cache: served from storage, upstream untouched")
    void cacheOnlyHitServesWithoutUpstream() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(ARTIFACT_KEY, new Content.From(CACHED_BYTES)).join();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final CacheOnlyTestSlice slice = new CacheOnlyTestSlice(
            countingUpstream(upstreamCalls), storage
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            cacheOnlyHeaders(), Content.EMPTY
        ).get(5, TimeUnit.SECONDS);
        assertEquals(RsStatus.OK, resp.status(), "warm cache serves 200");
        assertArrayEquals(
            CACHED_BYTES, resp.body().asBytes(),
            "cached bytes are returned verbatim"
        );
        assertEquals(0, upstreamCalls.get(), "upstream must never be contacted");
    }

    @Test
    @Timeout(10)
    @DisplayName("cache-only + cold cache: 404, upstream untouched")
    void cacheOnlyMissReturns404WithoutUpstream() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final CacheOnlyTestSlice slice = new CacheOnlyTestSlice(
            countingUpstream(upstreamCalls), storage
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            cacheOnlyHeaders(), Content.EMPTY
        ).get(5, TimeUnit.SECONDS);
        assertEquals(RsStatus.NOT_FOUND, resp.status(), "cold cache answers 404");
        assertEquals(0, upstreamCalls.get(), "upstream must never be contacted");
    }

    @Test
    @Timeout(10)
    @DisplayName("cache-only WITHOUT internal-routing header is ignored")
    void cacheOnlyWithoutInternalRoutingIsIgnored() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final CacheOnlyTestSlice slice = new CacheOnlyTestSlice(
            countingUpstream(upstreamCalls), storage
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.from(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true"),
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);
        assertEquals(
            RsStatus.OK, resp.status(),
            "without the internal-routing header the request follows the normal flow"
        );
        assertEquals(
            1, upstreamCalls.get(),
            "normal flow reaches the upstream on a cache miss"
        );
    }

    private static Headers cacheOnlyHeaders() {
        return Headers.from(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"))
            .copy()
            .add(new Header(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true"));
    }

    private static Slice countingUpstream(final AtomicInteger calls) {
        return (line, headers, content) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/java-archive")
                    .body("upstream-bytes".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        };
    }

    /**
     * Minimal storage-backed subclass: every path cacheable.
     */
    private static final class CacheOnlyTestSlice extends BaseCachedProxySlice {

        CacheOnlyTestSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }
}
