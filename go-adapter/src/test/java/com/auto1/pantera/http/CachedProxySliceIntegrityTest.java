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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the go-adapter's {@code CachedProxySlice} primary
 * ({@code *.zip}) stream-through path (WI-07 §9.5, T-P08).
 *
 * <p>WS4-go.1: the GOPROXY protocol has no checksum sidecar for module
 * archives, so this path streams + caches + single-flights but performs
 * <strong>no</strong> integrity verification — there is deliberately no
 * assertion here about a {@code .ziphash} sidecar (removed inert claim).
 * Genuine {@code h1:} dirhash verification against the sumdb returns as a
 * separate, deferred workstream once the sumdb proxy lands.
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
    @DisplayName("zip streams to the client, caches on disk, and makes zero .ziphash requests")
    void streamsAndCachesWithoutSidecarRequests() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(MODULE_ZIP);
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
            "first request streams module bytes to the client"
        );
        Thread.sleep(200);
        assertTrue(storage.exists(MODULE_KEY).join(), "primary cached on disk");
        assertEquals(
            0, origin.ziphashCalls(),
            "no .ziphash sidecar was ever requested (inert claim removed)"
        );
    }

    @Test
    @DisplayName("second GET is served from cache, no additional upstream call")
    void secondRequestServedFromCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(MODULE_ZIP);
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
            "second request serves cached bytes"
        );
        assertEquals(
            upstreamCallsBefore,
            origin.primaryCalls(),
            "second request did not hit upstream"
        );
        assertEquals(
            0, origin.ziphashCalls(),
            "no .ziphash sidecar was ever requested (inert claim removed)"
        );
    }

    @Test
    @DisplayName("concurrent cold fetches collapse to one upstream call (T-P08 single-flight)")
    void concurrentColdFetches_singleFlightCollapses() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakeGoUpstream origin = new FakeGoUpstream(MODULE_ZIP);
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
        assertEquals(
            0, origin.ziphashCalls(),
            "no .ziphash sidecar was ever requested (inert claim removed)"
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

    /**
     * Minimal fake Go upstream: serves the primary zip on artifact paths
     * and counts calls, distinguishing genuine primary-artifact requests
     * from any {@code .ziphash} request so tests can assert the latter
     * never happens (WS4-go.1: no sidecar exists in GOPROXY).
     */
    private static final class FakeGoUpstream implements Slice {
        private final byte[] primary;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final AtomicInteger ziphashCalls = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean hold =
            new java.util.concurrent.atomic.AtomicBoolean();

        FakeGoUpstream(final byte[] primary) {
            this.primary = primary;
        }

        int primaryCalls() {
            return this.primaryCalls.get();
        }

        int ziphashCalls() {
            return this.ziphashCalls.get();
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
                this.ziphashCalls.incrementAndGet();
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
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
