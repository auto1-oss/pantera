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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.CacheTimeControl;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link GoMetadataBaseLoader} — the WS4-go.2 TTL-cached,
 * single-flighted, serve-stale-on-failure loader for the Go proxy
 * {@code @v/list} / {@code @latest} base documents.
 *
 * <p>TTL semantics are proven with a fake, controllable {@code updated-at}
 * timestamp (see {@link FakeMetaStorage}) — never wall-clock, per the
 * project's testing doctrine. Coalescing is proven with invocation counts
 * and a completion gate, never a duration bound.</p>
 *
 * @since 2.3.0
 */
final class GoMetadataBaseLoaderTest {

    @Test
    void warmCacheServesWithoutAnyUpstreamCall() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.put("/mod/@v/list", "v1.0.0\n");
        final Storage storage = new InMemoryStorage();
        final GoMetadataBaseLoader loader = newLoader(upstream, storage);
        final Key key = new KeyFromPath("/mod/@v/list");

        final GoMetadataBaseLoader.Outcome first =
            loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(first.isAvailable(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            new String(first.body(), StandardCharsets.UTF_8),
            new IsEqual<>("v1.0.0\n")
        );
        // The stream-through cache write races the caller's completion (it
        // tees bytes to the caller while saving a copy in the background) —
        // wait for it to land before proving the second call is a cache hit.
        awaitPersisted(storage, key);

        final GoMetadataBaseLoader.Outcome second =
            loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(second.isAvailable(), new IsEqual<>(true));
        MatcherAssert.assertThat(second.stale(), new IsEqual<>(false));

        MatcherAssert.assertThat(
            "second request must be served entirely from cache",
            upstream.hits("/mod/@v/list"),
            new IsEqual<>(1)
        );
    }

    @Test
    void servesStaleCopyWhenUpstreamFailsAfterTtlExpiry() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.put("/mod/@v/list", "v1.0.0\n");
        final FakeMetaStorage storage = new FakeMetaStorage(new InMemoryStorage());
        final GoMetadataBaseLoader loader = newLoader(upstream, storage);
        final Key key = new KeyFromPath("/mod/@v/list");

        loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        awaitPersisted(storage, key);
        MatcherAssert.assertThat(upstream.hits("/mod/@v/list"), new IsEqual<>(1));

        // Force TTL expiry, then take upstream down.
        storage.stamp(key, Instant.now().minus(13, ChronoUnit.HOURS));
        upstream.fail(true);

        final GoMetadataBaseLoader.Outcome outcome =
            loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a stale-but-present copy must still be served on upstream failure",
            outcome.isAvailable(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(outcome.stale(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            new String(outcome.body(), StandardCharsets.UTF_8),
            new IsEqual<>("v1.0.0\n")
        );
        MatcherAssert.assertThat(
            "the expired entry must have triggered exactly one refresh attempt",
            upstream.hits("/mod/@v/list"), new IsEqual<>(2)
        );
    }

    @Test
    void refetchesAfterTtlExpiryWhenUpstreamChanged() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.put("/mod/@v/list", "v1.0.0\n");
        final FakeMetaStorage storage = new FakeMetaStorage(new InMemoryStorage());
        final GoMetadataBaseLoader loader = newLoader(upstream, storage);
        final Key key = new KeyFromPath("/mod/@v/list");

        loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        awaitPersisted(storage, key);

        storage.stamp(key, Instant.now().minus(13, ChronoUnit.HOURS));
        upstream.put("/mod/@v/list", "v1.0.0\nv1.1.0\n");

        final GoMetadataBaseLoader.Outcome outcome =
            loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(outcome.stale(), new IsEqual<>(false));
        MatcherAssert.assertThat(
            new String(outcome.body(), StandardCharsets.UTF_8),
            new IsEqual<>("v1.0.0\nv1.1.0\n")
        );
        MatcherAssert.assertThat(
            "an entry past its TTL must trigger a fresh upstream fetch",
            upstream.hits("/mod/@v/list"), new IsEqual<>(2)
        );
    }

    @Test
    void doesNotRefetchWithinTtl() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.put("/mod/@v/list", "v1.0.0\n");
        final FakeMetaStorage storage = new FakeMetaStorage(new InMemoryStorage());
        final GoMetadataBaseLoader loader = newLoader(upstream, storage);
        final Key key = new KeyFromPath("/mod/@v/list");

        loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        awaitPersisted(storage, key);
        storage.stamp(key, Instant.now().minus(1, ChronoUnit.HOURS));

        loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "an entry within its TTL must not trigger a re-fetch",
            upstream.hits("/mod/@v/list"), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(10)
    void singleFlightCollapsesConcurrentColdMissesToOneUpstreamCall() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.put("/mod/@v/list", "v1.0.0\n");
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        upstream.gate(gate);
        final GoMetadataBaseLoader loader = newLoader(upstream, new InMemoryStorage());

        final int concurrency = 8;
        final List<CompletableFuture<GoMetadataBaseLoader.Outcome>> futures =
            new ArrayList<>(concurrency);
        for (int idx = 0; idx < concurrency; idx++) {
            futures.add(loader.load("/mod/@v/list", "mod"));
        }
        // All N callers are now parked behind the leader's single in-flight
        // fetch (gated below) — proves coalescing, not just "fast enough".
        gate.complete(null);
        for (final CompletableFuture<GoMetadataBaseLoader.Outcome> future : futures) {
            final GoMetadataBaseLoader.Outcome outcome = future.get(5, TimeUnit.SECONDS);
            MatcherAssert.assertThat(outcome.isAvailable(), new IsEqual<>(true));
            MatcherAssert.assertThat(
                new String(outcome.body(), StandardCharsets.UTF_8),
                new IsEqual<>("v1.0.0\n")
            );
        }
        MatcherAssert.assertThat(
            concurrency + " concurrent cold misses must collapse onto one upstream call",
            upstream.hits("/mod/@v/list"), new IsEqual<>(1)
        );
    }

    @Test
    void coldMissWithUpstreamFailureForwardsUpstreamStatus() throws Exception {
        final ScriptedUpstream upstream = new ScriptedUpstream();
        upstream.fail(true);
        final GoMetadataBaseLoader loader = newLoader(upstream, new InMemoryStorage());

        final GoMetadataBaseLoader.Outcome outcome =
            loader.load("/mod/@v/list", "mod").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(outcome.isAvailable(), new IsEqual<>(false));
        MatcherAssert.assertThat(outcome.status(), new IsEqual<>(RsStatus.BAD_GATEWAY));
    }

    private static GoMetadataBaseLoader newLoader(final Slice upstream, final Storage storage) {
        final Cache cache = new FromStorageCache(storage);
        return new GoMetadataBaseLoader(upstream, cache, Optional.of(storage), "go-test");
    }

    /**
     * Poll until {@code key} is durably present in {@code storage}. The
     * stream-through cache write (see {@code FromStorageCache}) tees bytes
     * to the caller while saving a copy in the background, so the write is
     * not guaranteed durable the instant the caller's future completes —
     * poll for the eventual state rather than asserting instantly, per the
     * project's testing doctrine for shared/background completions.
     */
    private static void awaitPersisted(final Storage storage, final Key key) throws Exception {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!storage.exists(key).get(1, TimeUnit.SECONDS)) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Cache entry for " + key.string() + " never persisted");
            }
            Thread.sleep(2);
        }
    }

    /**
     * Storage decorator that lets a test stamp a controllable
     * {@code updated-at} instant for a given key, overriding whatever
     * {@link InMemoryStorage#metadata} would otherwise report (it never
     * tracks write time — only size). This is how {@link CacheTimeControl}
     * ({@code storage.metadata(item)}-driven) TTL expiry is exercised
     * deterministically, without any wall-clock sleep.
     */
    private static final class FakeMetaStorage extends Storage.Wrap {

        private final Map<String, Instant> stamped = new ConcurrentHashMap<>();

        FakeMetaStorage(final Storage delegate) {
            super(delegate);
        }

        void stamp(final Key key, final Instant updatedAt) {
            this.stamped.put(key.string(), updatedAt);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            final Instant instant = this.stamped.get(key.string());
            if (instant == null) {
                return super.metadata(key);
            }
            return CompletableFuture.completedFuture(new Meta() {
                @Override
                public <T> T read(final ReadOperator<T> opr) {
                    final Map<String, String> raw = new HashMap<>();
                    Meta.OP_UPDATED_AT.put(raw, instant);
                    return opr.take(raw);
                }
            });
        }
    }

    /** Scripted upstream {@link Slice}: canned bodies, hit counts, an
     * optional failure mode, and an optional completion gate so tests can
     * park N concurrent callers behind one in-flight fetch. */
    private static final class ScriptedUpstream implements Slice {

        private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        private volatile CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);
        private final AtomicBoolean failing = new AtomicBoolean(false);

        void put(final String path, final String body) {
            this.bodies.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        void fail(final boolean value) {
            this.failing.set(value);
        }

        void gate(final CompletableFuture<Void> value) {
            this.gate = value;
        }

        int hits(final String path) {
            final AtomicInteger counter = this.hits.get(path);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            return this.gate.thenApply(ignored -> {
                if (this.failing.get()) {
                    return ResponseBuilder.badGateway().build();
                }
                final byte[] content = this.bodies.get(path);
                if (content == null) {
                    return ResponseBuilder.notFound().build();
                }
                return ResponseBuilder.ok().body(content).build();
            }).toCompletableFuture();
        }
    }
}
