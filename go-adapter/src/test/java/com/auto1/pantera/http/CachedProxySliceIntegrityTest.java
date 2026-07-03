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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that the go-adapter's {@code CachedProxySlice} routes
 * primary {@code *.zip} writes through
 * {@link com.auto1.pantera.http.cache.ProxyCacheWriter} (WI-07 §9.5,
 * WI-post-07).
 *
 * @since 2.2.0
 */
final class CachedProxySliceIntegrityTest {

    /** Canonical Go module zip bytes. */
    private static final byte[] MODULE_ZIP =
        "go module archive body".getBytes(StandardCharsets.UTF_8);

    /** Request path for the module zip. */
    private static final String MODULE_PATH =
        "/example.com/test/@v/v1.0.0.zip";

    /** Cache key under which the module lands (no leading slash). */
    private static final Key MODULE_KEY =
        new Key.From("example.com/test/@v/v1.0.0.zip");

    @Test
    @DisplayName("upstream .ziphash mismatch → bytes streamed; cache stays empty; integrity metric increments (T-P08)")
    void ziphashMismatch_streamsButDoesNotCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(
            MODULE_ZIP,
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        );
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, MODULE_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Stream-through (T-P08): bytes already in flight when verify runs,
        // so the client sees 200; cache stays empty so the next request
        // re-fetches cleanly. Integrity metric still fires for observability.
        assertEquals(RsStatus.OK, response.status(), "200 (bytes streamed to client)");
        assertArrayEquals(
            MODULE_ZIP,
            response.body().asBytesFuture().join(),
            "module bytes streamed to client"
        );
        Thread.sleep(200);
        assertFalse(storage.exists(MODULE_KEY).join(), "primary NOT in storage");
        assertFalse(
            storage.exists(new Key.From(MODULE_KEY.string() + ".sha256")).join(),
            "sidecar NOT in storage"
        );
        final Counter counter = registry.find("pantera.proxy.cache.integrity_failure")
            .tags(Tags.of("repo", "go-proxy-test", "algo", "sha256"))
            .counter();
        assertNotNull(counter, "integrity-failure counter registered");
        assertEquals(1.0, counter.count(), "counter incremented once");
    }

    @Test
    @DisplayName("matching .ziphash → primary + sidecar persisted; second GET served from cache")
    void matchingZiphash_persistsAndServesFromCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(MODULE_ZIP, sha256Hex(MODULE_ZIP));
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final Response first = slice.response(
            new RequestLine(RqMethod.GET, MODULE_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, first.status(), "first request 200");
        assertArrayEquals(
            MODULE_ZIP,
            first.body().asBytesFuture().join(),
            "first request serves module bytes"
        );
        assertTrue(storage.exists(MODULE_KEY).join(), "primary in storage");
        assertTrue(
            storage.exists(new Key.From(MODULE_KEY.string() + ".sha256")).join(),
            "sha256 sidecar in storage (written from .ziphash upstream)"
        );
        final int upstreamCallsBefore = origin.primaryCalls();
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, MODULE_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, second.status(), "second request 200");
        assertArrayEquals(
            MODULE_ZIP,
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
    @DisplayName("concurrent cold fetches collapse to one upstream call (T-P08 single-flight)")
    void concurrentColdFetches_singleFlightCollapses() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(MODULE_ZIP, sha256Hex(MODULE_ZIP));
        origin.holdPrimary(true);
        final CachedProxySlice slice = buildSlice(origin, storage, registry);

        final int callers = 8;
        final java.util.List<CompletableFuture<Response>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(slice.response(
                new RequestLine(RqMethod.GET, MODULE_PATH),
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
                MODULE_ZIP,
                resp.body().asBytesFuture().join(),
                "every caller receives full module bytes"
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
            Cache.NOP,
            Optional.empty(),
            Optional.of(storage),
            "go-proxy-test",
            "go-proxy",
            NoopCooldownService.INSTANCE,
            noopInspector()
        );
        injectTestWriter(slice, storage, "go-proxy-test", registry);
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

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
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
     * Minimal fake Go upstream: serves primary zip on artifact paths, and
     * the SHA-256 hex on {@code .ziphash} paths. Counts primary GETs so
     * tests can confirm the second request is cache-only.
     */
    private static final class FakeGoUpstream implements Slice {
        private final byte[] primary;
        private final String sha256Hex;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean hold =
            new java.util.concurrent.atomic.AtomicBoolean();

        FakeGoUpstream(final byte[] primary, final String sha256Hex) {
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
            if (path.endsWith(".ziphash")) {
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
