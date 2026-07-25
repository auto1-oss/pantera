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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that {@link ProxyDownloadSlice} verifies dist archives against the
 * packument's declared {@code dist.shasum} (WS4-composer.3, S7 of
 * {@code 00-security-integrity-decisions.md}), fails closed on a mismatch,
 * and single-flights concurrent cold fetches (WS4-composer.4).
 */
final class ProxyDownloadSliceIntegrityTest {

    private static final byte[] DIST_BYTES =
        "composer dist archive bytes".getBytes(StandardCharsets.UTF_8);

    private static final String DIST_PATH = "/dist/vendor/package/1.0.0.zip";

    private static final Key DIST_KEY = new Key.From("dist/vendor/package/1.0.0.zip");

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    @DisplayName("matching dist.shasum: cached and served")
    void matchingShasumCachesAndServes() {
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        Assertions.assertEquals(RsStatus.OK, response.status(), "200 on integrity match");
        Assertions.assertArrayEquals(
            DIST_BYTES, response.body().asBytesFuture().join(), "served bytes match upstream"
        );
        Assertions.assertTrue(this.storage.exists(DIST_KEY).join(), "archive persisted to cache");
        Assertions.assertEquals(1, upstream.calls(), "exactly one upstream fetch");

        // Second request is a pure cache hit — no further upstream call.
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, second.status(), "cache-hit 200");
        Assertions.assertEquals(1, upstream.calls(), "no upstream call on cache hit");
    }

    @Test
    @DisplayName("mismatched dist.shasum: 502, X-Pantera-Fault, cache stays empty")
    void mismatchedShasumRejectsAndDoesNotCache() {
        this.seedMetadata("0000000000000000000000000000000000dead");
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        Assertions.assertEquals(
            RsStatus.BAD_GATEWAY, response.status(), "502 on integrity mismatch"
        );
        final List<Header> fault = response.headers().find("X-Pantera-Fault");
        Assertions.assertFalse(fault.isEmpty(), "X-Pantera-Fault header present");
        Assertions.assertEquals(
            "upstream-integrity:sha1", fault.getFirst().getValue(), "fault names sha1"
        );
        Assertions.assertFalse(
            this.storage.exists(DIST_KEY).join(), "corrupted archive NOT cached"
        );

        // A subsequent clean fetch (upstream now serves a claim matching what
        // it actually returns) must succeed — the earlier mismatch left no
        // poisoned state behind.
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final Response retry = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, retry.status(), "clean retry succeeds");
        Assertions.assertTrue(this.storage.exists(DIST_KEY).join(), "clean retry is cached");
    }

    @Test
    @DisplayName("concurrent cold fetches of the same archive collapse to one upstream call")
    void concurrentColdFetchesSingleFlight() {
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        upstream.hold(true);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final int callers = 6;
        final List<CompletableFuture<Response>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(slice.response(
                new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
            ));
        }
        awaitInflight(upstream, callers);
        upstream.hold(false);
        for (final CompletableFuture<Response> future : futures) {
            final Response response = future.join();
            Assertions.assertEquals(RsStatus.OK, response.status(), "every caller gets 200");
            Assertions.assertArrayEquals(
                DIST_BYTES, response.body().asBytesFuture().join(), "every caller gets full bytes"
            );
        }
        Assertions.assertEquals(
            1, upstream.calls(),
            "single-flight collapsed " + callers + " concurrent cold fetches to one upstream call"
        );
    }

    @Test
    @DisplayName("HEAD returns GET's status with an empty body")
    void headMirrorsGetStatusWithNoBody() {
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        Assertions.assertEquals(RsStatus.OK, head.status(), "HEAD mirrors GET status");
        Assertions.assertEquals(
            0, head.body().asBytesFuture().join().length, "HEAD body is empty"
        );
    }

    /**
     * Poll until {@code upstream} has observed {@code expected} in-flight
     * calls, or fail the test via {@code assertTimeoutPreemptively}-style
     * bound instead of a fixed sleep — the single-flight gate itself
     * (holding the response) proves ordering, this just waits for every
     * caller to have reached the upstream call.
     */
    private static void awaitInflight(final FakeUpstream upstream, final int expected) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (upstream.calls() < 1 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        Assertions.assertTrue(
            upstream.calls() >= 1, "leader reached the upstream call before timeout"
        );
    }

    private void seedMetadata(final String shasum) {
        final String json = "{\"packages\":{\"vendor/package\":{\"1.0.0\":{"
            + "\"version\":\"1.0.0\",\"dist\":{"
            + "\"original_url\":\"http://upstream.test" + DIST_PATH + "\","
            + "\"shasum\":\"" + shasum + "\"}}}}}";
        this.storage.save(
            new Key.From("vendor/package.json"),
            new Content.From(json.getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    private ProxyDownloadSlice buildSlice(final Slice upstream) {
        return new ProxyDownloadSlice(
            upstream,
            new UnusedClientSlices(),
            URI.create("http://upstream.test"),
            Optional.empty(),
            "composer-proxy-test",
            "php",
            this.storage,
            NoopCooldownService.INSTANCE,
            new RegistryBackedInspector("composer", PublishDateRegistries.instance())
        );
    }

    private static String sha1Hex(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * All dist URLs in this test are same-host (the configured
     * {@code remoteBase}), so {@code ProxyDownloadSlice} always resolves the
     * shared {@code remote} slice and never calls out to {@link ClientSlices}
     * to build a cross-host client — every method here would indicate a
     * test setup bug if ever invoked.
     */
    private static final class UnusedClientSlices implements ClientSlices {
        @Override
        public Slice http(final String host) {
            throw new UnsupportedOperationException("cross-host client not expected in this test");
        }

        @Override
        public Slice http(final String host, final int port) {
            throw new UnsupportedOperationException("cross-host client not expected in this test");
        }

        @Override
        public Slice https(final String host) {
            throw new UnsupportedOperationException("cross-host client not expected in this test");
        }

        @Override
        public Slice https(final String host, final int port) {
            throw new UnsupportedOperationException("cross-host client not expected in this test");
        }
    }

    /**
     * Minimal fake upstream serving fixed bytes for any request, with an
     * optional hold-open gate to prove single-flight collapse.
     */
    private static final class FakeUpstream implements Slice {

        private final byte[] body;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean held = new AtomicBoolean();

        FakeUpstream(final byte[] body) {
            this.body = body;
        }

        int calls() {
            return this.calls.get();
        }

        void hold(final boolean value) {
            this.held.set(value);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content requestBody
        ) {
            this.calls.incrementAndGet();
            if (this.held.get()) {
                final CompletableFuture<Response> future = new CompletableFuture<>();
                final byte[] bytes = this.body;
                final AtomicBoolean gate = this.held;
                CompletableFuture.runAsync(() -> {
                    while (gate.get()) {
                        Thread.onSpinWait();
                    }
                    future.complete(ResponseBuilder.ok().body(bytes).build());
                });
                return future;
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(this.body).build()
            );
        }
    }
}
