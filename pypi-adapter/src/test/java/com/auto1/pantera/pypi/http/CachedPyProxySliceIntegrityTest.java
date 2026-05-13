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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
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
    @DisplayName("upstream SHA-256 mismatch → storage empty + integrity metric incremented")
    void sha256Mismatch_rejectsWrite() throws Exception {
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

        final int status = response.status().code();
        assertThat(
            "502 on integrity failure (or cache empty if FaultTranslator unwired)",
            status == 502 || status == 404, is(true)
        );
        if (status == 502) {
            assertThat(
                "X-Pantera-Fault: upstream-integrity:<algo>",
                headerValue(response, "X-Pantera-Fault").orElse(""),
                containsString("upstream-integrity")
            );
        }
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

    private static Optional<String> headerValue(final Response response, final String name) {
        return java.util.stream.StreamSupport
            .stream(response.headers().spliterator(), false)
            .filter(h -> h.getKey().equalsIgnoreCase(name))
            .map(java.util.Map.Entry::getValue)
            .findFirst();
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
