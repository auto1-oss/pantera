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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that composer's {@code CachedProxySlice} routes
 * primary {@code *.zip}/{@code *.tar}/{@code *.phar} writes through
 * {@link com.auto1.pantera.http.cache.ProxyCacheWriter} (WI-07 §9.5,
 * WI-post-07).
 *
 * @since 2.2.0
 */
final class CachedProxySliceIntegrityTest {

    /** Canonical composer dist zip bytes. */
    private static final byte[] DIST_BYTES =
        "composer dist archive".getBytes(StandardCharsets.UTF_8);

    /** Request path for the dist zip. */
    private static final String DIST_PATH =
        "/dists/vendor/package/sha/vendor-package-1.0.0.zip";

    /** Cache key for the dist zip (leading slash stripped). */
    private static final Key DIST_KEY =
        new Key.From("dists/vendor/package/sha/vendor-package-1.0.0.zip");

    @Test
    @DisplayName("upstream .sha256 mismatch → bytes streamed; cache stays empty; integrity metric increments (T-P07)")
    void sha256Mismatch_streamsButDoesNotCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeComposerUpstream origin = new FakeComposerUpstream(
            DIST_BYTES,
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        );
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Stream-through (T-P07): bytes were already streamed when verify ran,
        // so the client sees 200; cache stays empty so the next request
        // re-fetches cleanly. Integrity counter still fires for observability.
        assertEquals(RsStatus.OK, response.status(), "200 (bytes streamed to client)");
        assertArrayEquals(
            DIST_BYTES,
            response.body().asBytesFuture().join(),
            "dist bytes streamed to client"
        );
        Thread.sleep(200);
        assertFalse(storage.exists(DIST_KEY).join(), "primary NOT in storage");
        assertFalse(
            storage.exists(new Key.From(DIST_KEY.string() + ".sha256")).join(),
            "sha256 sidecar NOT in storage"
        );
        final Counter counter = registry.find("pantera.proxy.cache.integrity_failure")
            .tags(Tags.of("repo", "composer-proxy-test", "algo", "sha256"))
            .counter();
        assertNotNull(counter, "integrity-failure counter registered");
        assertEquals(1.0, counter.count(), "counter incremented once");
    }

    @Test
    @DisplayName("matching .sha256 → primary + sidecar persisted; second GET served from cache")
    void matchingSidecar_persistsAndServesFromCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeComposerUpstream origin = new FakeComposerUpstream(
            DIST_BYTES, sha256Hex(DIST_BYTES)
        );
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final Response first = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, first.status(), "first request 200");
        assertArrayEquals(
            DIST_BYTES,
            first.body().asBytesFuture().join(),
            "first request serves dist bytes"
        );
        assertTrue(storage.exists(DIST_KEY).join(), "primary in storage");
        assertTrue(
            storage.exists(new Key.From(DIST_KEY.string() + ".sha256")).join(),
            "sha256 sidecar in storage"
        );
        final int upstreamCallsBefore = origin.primaryCalls();
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, second.status(), "second request 200 from cache");
        assertArrayEquals(
            DIST_BYTES,
            second.body().asBytesFuture().join(),
            "second request cached bytes"
        );
        assertEquals(
            upstreamCallsBefore,
            origin.primaryCalls(),
            "second request did not hit upstream"
        );
    }

    @Test
    @DisplayName("concurrent cold fetches collapse to one upstream call (T-P07 single-flight)")
    void concurrentColdFetches_singleFlightCollapses() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeComposerUpstream origin = new FakeComposerUpstream(
            DIST_BYTES, sha256Hex(DIST_BYTES)
        );
        origin.holdPrimary(true);
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final int callers = 8;
        final java.util.List<CompletableFuture<Response>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(slice.response(
                new RequestLine(RqMethod.GET, DIST_PATH),
                Headers.EMPTY,
                Content.EMPTY
            ));
        }
        Thread.sleep(100);
        origin.holdPrimary(false);
        for (final CompletableFuture<Response> f : futures) {
            final Response resp = f.join();
            assertEquals(RsStatus.OK, resp.status(), "every caller gets 200");
            assertArrayEquals(
                DIST_BYTES,
                resp.body().asBytesFuture().join(),
                "every caller receives full dist bytes"
            );
        }
        assertEquals(
            1, origin.primaryCalls(),
            "single-flight collapsed " + callers + " concurrent cold fetches to one upstream call"
        );
    }

    private static CachedProxySlice buildSlice(
        final Slice origin, final Storage storage, final MeterRegistry registry
    ) throws Exception {
        final CachedProxySlice slice = new CachedProxySlice(
            origin,
            new AstoRepository(storage),
            new FromStorageCache(storage),
            Optional.empty(),
            "composer-proxy-test",
            "http://localhost:8080",
            "https://packagist.example/composer"
        );
        injectTestWriter(slice, storage, "composer-proxy-test", registry);
        return slice;
    }

    private static void injectTestWriter(
        final CachedProxySlice slice,
        final Storage storage,
        final String repoName,
        final MeterRegistry registry
    ) throws Exception {
        final Field f = CachedProxySlice.class.getDeclaredField("cacheWriter");
        f.setAccessible(true);
        f.set(slice, new com.auto1.pantera.http.cache.ProxyCacheWriter(
            storage, repoName, registry
        ));
    }

    private static String sha256Hex(final byte[] body) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * Minimal fake composer upstream serving primary dist on any path NOT
     * ending in {@code .sha256}, and the claimed digest hex otherwise.
     * Counts primary GETs so the test can assert the second request is
     * cache-only.
     */
    private static final class FakeComposerUpstream implements Slice {
        private final byte[] primary;
        private final String sha256Hex;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean hold =
            new java.util.concurrent.atomic.AtomicBoolean();

        FakeComposerUpstream(final byte[] primary, final String sha256Hex) {
            this.primary = primary;
            this.sha256Hex = sha256Hex;
        }

        int primaryCalls() {
            return this.primaryCalls.get();
        }

        void holdPrimary(final boolean hold) {
            this.hold.set(hold);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            if (path.endsWith(".sha256")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(this.sha256Hex.getBytes(StandardCharsets.UTF_8))
                        .build()
                );
            }
            this.primaryCalls.incrementAndGet();
            if (this.hold.get()) {
                final CompletableFuture<Response> future = new CompletableFuture<>();
                final byte[] primaryBytes = this.primary;
                final java.util.concurrent.atomic.AtomicBoolean held = this.hold;
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        while (held.get()) {
                            Thread.sleep(20);
                        }
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    future.complete(
                        ResponseBuilder.ok().body(primaryBytes).build()
                    );
                });
                return future;
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(this.primary)
                    .build()
            );
        }
    }
}
