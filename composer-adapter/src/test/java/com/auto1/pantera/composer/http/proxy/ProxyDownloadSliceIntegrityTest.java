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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that {@link ProxyDownloadSlice} verifies dist archives against the
 * packument's declared {@code dist.shasum} (WS4-composer.3, S7 of
 * {@code 00-security-integrity-decisions.md}) inside the stream-through
 * cache tee — a mismatch keeps the cache empty — and single-flights
 * concurrent cold fetches (WS4-composer.4).
 *
 * <p>The dist is streamed through to the client while it is being verified
 * (see {@link ProxyDownloadSliceStreamingTest}), so the integrity outcome
 * is proven on the cache and on the upstream invocation count — never on
 * the in-flight response status, which is already committed when the
 * digest comparison runs.</p>
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
    @Timeout(20)
    @DisplayName("matching dist.shasum: cached and served")
    void matchingShasumCachesAndServes() throws Exception {
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
        // The cache commit lands once the stream completes — poll for it.
        awaitCached(this.storage, DIST_KEY);
        Assertions.assertArrayEquals(
            DIST_BYTES,
            this.storage.value(DIST_KEY).join().asBytesFuture().join(),
            "cached bytes are the verified upstream bytes"
        );
        Assertions.assertEquals(1, upstream.calls(), "exactly one upstream fetch");

        // Second request is a pure cache hit — no further upstream call.
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, second.status(), "cache-hit 200");
        Assertions.assertArrayEquals(
            DIST_BYTES, second.body().asBytesFuture().join(), "cache hit serves the same bytes"
        );
        Assertions.assertEquals(1, upstream.calls(), "no upstream call on cache hit");
    }

    @Test
    @Timeout(20)
    @DisplayName("mismatched dist.shasum: streamed to the client, never cached")
    void mismatchedShasumIsNeverCached() throws Exception {
        this.seedMetadata("0000000000000000000000000000000000dead");
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        // Stream-through: the response is committed before the digest can
        // be compared, so the client receives the bytes (and verifies
        // dist.shasum itself, as Composer always does) ...
        Assertions.assertEquals(
            RsStatus.OK, response.status(), "stream-through response is already committed"
        );
        Assertions.assertArrayEquals(
            DIST_BYTES, response.body().asBytesFuture().join(), "the upstream bytes are relayed"
        );
        // ... but the corrupted archive must never reach the cache.
        Assertions.assertFalse(
            this.storage.exists(DIST_KEY).join(), "corrupted archive NOT cached"
        );

        // A subsequent clean fetch (the claim now matches what the upstream
        // returns) must go upstream again and succeed — had the mismatching
        // archive been cached, this would have been a cache hit with no
        // second upstream call.
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final Response retry = slice.response(
            new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, retry.status(), "clean retry succeeds");
        Assertions.assertArrayEquals(
            DIST_BYTES, retry.body().asBytesFuture().join(), "clean retry relays the bytes"
        );
        Assertions.assertEquals(
            2, upstream.calls(), "the retry re-fetched upstream: nothing poisoned was cached"
        );
        awaitCached(this.storage, DIST_KEY);
    }

    @Test
    @Timeout(20)
    @DisplayName("concurrent cold fetches of the same archive collapse to one upstream call")
    void concurrentColdFetchesSingleFlight() {
        this.seedMetadata(sha1Hex(DIST_BYTES));
        final FakeUpstream upstream = new FakeUpstream(DIST_BYTES);
        upstream.hold(true);
        final ProxyDownloadSlice slice = this.buildSlice(upstream);

        final int callers = 6;
        final List<CompletableFuture<Served>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            // Consume each body as soon as its response arrives: the leader's
            // stream-through body drives the cache commit that releases the
            // followers, so no caller may wait on another's body.
            futures.add(
                slice.response(
                    new RequestLine(RqMethod.GET, DIST_PATH), Headers.EMPTY, Content.EMPTY
                ).thenCompose(
                    resp -> resp.body().asBytesFuture().thenApply(
                        bytes -> new Served(resp.status(), bytes)
                    )
                )
            );
        }
        awaitInflight(upstream);
        upstream.hold(false);
        for (final CompletableFuture<Served> future : futures) {
            final Served served = future.join();
            Assertions.assertEquals(RsStatus.OK, served.status(), "every caller gets 200");
            Assertions.assertArrayEquals(
                DIST_BYTES, served.bytes(), "every caller gets full bytes"
            );
        }
        Assertions.assertEquals(
            1, upstream.calls(),
            "single-flight collapsed " + callers + " concurrent cold fetches to one upstream call"
        );
    }

    @Test
    @Timeout(20)
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
     * Poll until the leader has reached the upstream call. The single-flight
     * gate itself (holding the response) proves ordering; this just waits
     * for the leader to be in flight before releasing the hold.
     */
    private static void awaitInflight(final FakeUpstream upstream) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (upstream.calls() < 1 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        Assertions.assertTrue(
            upstream.calls() >= 1, "leader reached the upstream call before timeout"
        );
    }

    /**
     * Poll until {@code key} is durably present in {@code storage}: the
     * stream-through cache write commits after the client has consumed the
     * body, so poll for the eventual state instead of asserting instantly.
     */
    private static void awaitCached(final Storage storage, final Key key) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!storage.exists(key).join()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("dist " + key.string() + " was never committed to the cache");
            }
            Thread.sleep(5);
        }
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
     * A fully consumed response: status plus body bytes.
     */
    private record Served(RsStatus status, byte[] bytes) {
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
