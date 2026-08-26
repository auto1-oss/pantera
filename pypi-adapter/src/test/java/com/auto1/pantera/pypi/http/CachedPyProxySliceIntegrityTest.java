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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
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
import java.time.Duration;
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
 * Integration tests that {@link CachedPyProxySlice} routes primary
 * artefact writes through {@link com.auto1.pantera.http.cache.ProxyCacheWriter}
 * (WI-07 §9.5, WI-post-07).
 *
 * @since 2.2.0
 */
final class CachedPyProxySliceIntegrityTest {

    /** Canonical wheel body used in every test. */
    private static final byte[] WHEEL_BYTES =
        "pypi wheel body".getBytes(StandardCharsets.UTF_8);

    /** Cache key used for the wheel (leading slash stripped). */
    private static final Key WHEEL_KEY =
        new Key.From("alarmtime/alarmtime-0.1.5-py3-none-any.whl");

    /** Request path for the wheel (carries the leading slash). */
    private static final String WHEEL_PATH =
        "/alarmtime/alarmtime-0.1.5-py3-none-any.whl";

    @Test
    @DisplayName("upstream SHA-256 mismatch → bytes streamed to client; cache stays empty; integrity metric increments (T-P06)")
    void sha256Mismatch_streamsButDoesNotCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakePyUpstream origin = new FakePyUpstream(
            WHEEL_BYTES,
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            null,
            null
        );
        final CachedPyProxySlice slice = buildSlice(origin, storage, registry);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, WHEEL_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Stream-through (T-P06): the bytes were already in flight when the
        // verify step ran, so the client receives 200 with the wheel bytes.
        // The cache stays empty so the next request re-fetches cleanly; the
        // integrity-failure metric still fires for observability.
        assertEquals(RsStatus.OK, response.status(), "200 (bytes streamed to client)");
        // Drain the body so the verify-and-commit step runs and the integrity
        // counter increments.
        assertArrayEquals(
            WHEEL_BYTES,
            response.body().asBytesFuture().join(),
            "wheel bytes streamed to client"
        );
        // Wait briefly for the async commit step to drop the temp file.
        Thread.sleep(200);
        assertFalse(storage.exists(WHEEL_KEY).join(), "primary NOT in storage");
        assertFalse(
            storage.exists(new Key.From(WHEEL_KEY.string() + ".sha256")).join(),
            "sha256 sidecar NOT in storage"
        );
        final Counter counter = registry.find("pantera.proxy.cache.integrity_failure")
            .tags(Tags.of("repo", "pypi-proxy-test", "algo", "sha256"))
            .counter();
        assertNotNull(counter, "integrity-failure counter registered");
        assertEquals(1.0, counter.count(), "counter incremented once");
    }

    @Test
    @DisplayName("matching sidecars → primary + sha256 sidecar readable; second GET served from cache")
    void matchingSidecars_persistsAndServesFromCache() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakePyUpstream origin = new FakePyUpstream(
            WHEEL_BYTES,
            sha256Hex(WHEEL_BYTES),
            md5Hex(WHEEL_BYTES),
            null
        );
        final CachedPyProxySlice slice = buildSlice(origin, storage, registry);

        final Response first = slice.response(
            new RequestLine(RqMethod.GET, WHEEL_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, first.status(), "first request 200");
        assertArrayEquals(
            WHEEL_BYTES,
            first.body().asBytesFuture().join(),
            "first request serves wheel bytes"
        );
        assertTrue(storage.exists(WHEEL_KEY).join(), "primary in storage");
        assertArrayEquals(
            WHEEL_BYTES,
            storage.value(WHEEL_KEY).join().asBytes(),
            "primary bytes match"
        );
        assertTrue(
            storage.exists(new Key.From(WHEEL_KEY.string() + ".sha256")).join(),
            "sha256 sidecar in storage"
        );
        assertTrue(
            storage.exists(new Key.From(WHEEL_KEY.string() + ".md5")).join(),
            "md5 sidecar in storage"
        );

        final int upstreamCallsBefore = origin.primaryCalls();
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, WHEEL_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, second.status(), "second request 200 from cache");
        assertArrayEquals(
            WHEEL_BYTES,
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
    @DisplayName("concurrent cold fetches collapse to one upstream call (T-P06 single-flight)")
    void concurrentColdFetches_singleFlightCollapses() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakePyUpstream origin = new FakePyUpstream(
            WHEEL_BYTES, sha256Hex(WHEEL_BYTES), md5Hex(WHEEL_BYTES), null
        );
        // Latch upstream so all callers race the same cold key.
        origin.holdPrimary(true);
        final CachedPyProxySlice slice = buildSlice(origin, storage, registry);

        final int callers = 8;
        final java.util.List<CompletableFuture<Response>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(slice.response(
                new RequestLine(RqMethod.GET, WHEEL_PATH),
                Headers.EMPTY,
                Content.EMPTY
            ));
        }
        // Give followers a moment to enter the single-flight gate.
        Thread.sleep(100);
        origin.holdPrimary(false);
        for (final CompletableFuture<Response> f : futures) {
            final Response resp = f.join();
            assertEquals(RsStatus.OK, resp.status(), "every caller gets 200");
            assertArrayEquals(
                WHEEL_BYTES,
                resp.body().asBytesFuture().join(),
                "every caller receives full wheel bytes"
            );
        }
        assertEquals(
            1, origin.primaryCalls(),
            "single-flight collapsed " + callers + " concurrent cold fetches to one upstream call"
        );
    }

    @Test
    @DisplayName("HEAD on an uncached wheel returns 200 with no body and populates "
        + "the cache with the FULL bytes (not a phantom empty artifact) — WS4-pypi.8")
    void headOnUncachedWheel_populatesCacheWithoutCorruption() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final FakePyUpstream origin = new FakePyUpstream(
            WHEEL_BYTES, sha256Hex(WHEEL_BYTES), md5Hex(WHEEL_BYTES), null
        );
        final CachedPyProxySlice slice = buildSlice(origin, storage, registry);

        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, WHEEL_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, head.status(), "HEAD on uncached wheel returns 200");
        assertArrayEquals(
            new byte[0],
            head.body().asBytesFuture().join(),
            "HEAD response body must be empty"
        );
        assertTrue(
            storage.exists(WHEEL_KEY).join(),
            "HEAD on an uncached wheel must still populate the cache (real GET underneath)"
        );
        assertArrayEquals(
            WHEEL_BYTES,
            storage.value(WHEEL_KEY).join().asBytes(),
            "cached artifact must be the FULL wheel bytes, not a phantom empty write"
        );

        final int upstreamCallsAfterHead = origin.primaryCalls();
        final Response get = slice.response(
            new RequestLine(RqMethod.GET, WHEEL_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, get.status(), "subsequent GET 200 from the now-warm cache");
        assertArrayEquals(
            WHEEL_BYTES, get.body().asBytesFuture().join(), "subsequent GET serves full bytes"
        );
        assertEquals(
            upstreamCallsAfterHead, origin.primaryCalls(),
            "subsequent GET must be served from cache, not hit upstream again"
        );
    }

    @Test
    @DisplayName("HEAD on a missing artifact returns 404, never 405 (WS4-pypi.8)")
    void headOnMissingArtifact_returns404NotMethodNotAllowed() throws Exception {
        final Storage storage = new InMemoryStorage();
        final MeterRegistry registry = new SimpleMeterRegistry();
        final Slice alwaysNotFound = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final CachedPyProxySlice slice = buildSlice(alwaysNotFound, storage, registry);

        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, "/missing/missing-0.0.1-py3-none-any.whl"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.NOT_FOUND, head.status(), "missing artifact HEAD -> 404, never 405");
    }

    private static CachedPyProxySlice buildSlice(
        final Slice origin, final Storage storage, final MeterRegistry registry
    ) throws Exception {
        @SuppressWarnings("deprecation")
        final CachedPyProxySlice slice = new CachedPyProxySlice(
            origin,
            Optional.of(storage),
            Duration.ofHours(1),
            false,
            "pypi-proxy-test",
            "https://upstream.example/pypi",
            "pypi"
        );
        injectTestWriter(slice, storage, "pypi-proxy-test", registry);
        return slice;
    }

    private static void injectTestWriter(
        final CachedPyProxySlice slice,
        final Storage storage,
        final String repoName,
        final MeterRegistry registry
    ) throws Exception {
        final Field f = CachedPyProxySlice.class.getDeclaredField("cacheWriter");
        f.setAccessible(true);
        f.set(slice, new com.auto1.pantera.http.cache.ProxyCacheWriter(
            storage, repoName, registry
        ));
    }

    private static String sha256Hex(final byte[] body) {
        return hex("SHA-256", body);
    }

    private static String md5Hex(final byte[] body) {
        return hex("MD5", body);
    }

    private static String hex(final String algo, final byte[] body) {
        try {
            final MessageDigest md = MessageDigest.getInstance(algo);
            return HexFormat.of().formatHex(md.digest(body));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * Minimal fake PyPI upstream. Serves the primary wheel on non-sidecar
     * paths; returns per-algo hex on {@code .sha256}/{@code .md5}/{@code .sha512}
     * paths when configured. Counts primary GETs so tests can confirm the
     * second request is cache-only.
     */
    private static final class FakePyUpstream implements Slice {
        private final byte[] primary;
        private final String sha256;
        private final String md5;
        private final String sha512;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean hold =
            new java.util.concurrent.atomic.AtomicBoolean();

        FakePyUpstream(
            final byte[] primary,
            final String sha256,
            final String md5,
            final String sha512
        ) {
            this.primary = primary;
            this.sha256 = sha256;
            this.md5 = md5;
            this.sha512 = sha512;
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
                return serveOrNotFound(this.sha256);
            }
            if (path.endsWith(".md5")) {
                return serveOrNotFound(this.md5);
            }
            if (path.endsWith(".sha512")) {
                return serveOrNotFound(this.sha512);
            }
            this.primaryCalls.incrementAndGet();
            if (this.hold.get()) {
                // Spin-block in a background thread until released, simulating
                // a slow upstream so followers race the leader.
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

        private static CompletableFuture<Response> serveOrNotFound(final String hex) {
            if (hex == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(hex.getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        }
    }
}
