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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression-guard for the WI-post-05 SingleFlight migration of
 * {@link BaseCachedProxySlice}. Preserves the three behaviors previously
 * covered by {@code RequestDeduplicatorTest}: coalescing of concurrent loader
 * invocations, propagation of the shared terminal signal to every caller,
 * and independence of distinct keys.
 *
 * <p>The tests exercise the migrated path end-to-end — {@code fetchAndCache →
 * singleFlight.load(key, cacheResponse) → signalToResponse} — rather than
 * calling the {@code SingleFlight} helper directly. Testing {@link
 * SingleFlight} in isolation cannot catch a regression such as "the
 * coalescer was removed from the cache-write loader path" (e.g. a future
 * refactor that inlines {@code cacheResponse} back to per-call execution);
 * testing {@link BaseCachedProxySlice}'s observable cache-write count can.
 *
 * <p>The observable proxy for "loader invoked exactly once" is the number of
 * primary-key {@code Storage.save(key, content)} calls. {@code cacheResponse}
 * writes the primary artifact exactly once per invocation, so:
 *
 * <ul>
 *   <li>Pre-migration SIGNAL behavior (with a blocking loader covering the
 *       entire coalescing window): N concurrent callers ⇒ exactly 1 save.</li>
 *   <li>If the dedup is ever lost: N concurrent callers ⇒ N saves
 *       (one per caller's {@code cacheResponse}).</li>
 * </ul>
 *
 * <p>The coalescing window is kept open by <b>blocking the first loader</b>
 * via a gate on {@code Storage.save}: every caller attaches to the
 * SingleFlight entry before the leader's save ever completes. This matches
 * the pattern the legacy {@code RequestDeduplicatorTest} used (a
 * never-completing {@code blocker} future for the first call) — the
 * observable entities are different (save count vs fetch count) but the
 * coalescing semantics are the same.
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceDedupTest {

    /**
     * Primary artifact path shared by all callers in the coalescing tests.
     */
    private static final String ARTIFACT_PATH =
        "/com/example/foo/1.0/foo-1.0.jar";

    /**
     * Matching storage key for {@link #ARTIFACT_PATH}. Leading slash is
     * dropped by {@code KeyFromPath}.
     */
    private static final Key ARTIFACT_KEY =
        new Key.From("com/example/foo/1.0/foo-1.0.jar");

    /**
     * N concurrent GETs for the same cacheable path must invoke the
     * cache-write loader exactly once — observable as exactly one primary-key
     * storage {@code save} call. All N callers must receive a 200 response
     * after the leader's save completes.
     *
     * <p>The test forces every caller to attach to the SingleFlight entry
     * before the first loader can complete by blocking {@code Storage.save}
     * on a gate. This is equivalent to the never-complete {@code blocker}
     * pattern the legacy {@code RequestDeduplicatorTest} used.
     */
    @Test
    @Timeout(15)
    void concurrentRequestsShareOneCacheWrite() throws Exception {
        final int callers = 100;
        final byte[] body = "deduplicated body".getBytes();
        final CountDownLatch saveGate = new CountDownLatch(1);
        final Slice upstream = immediateOkUpstream(body);
        final GatedCountingStorage storage = new GatedCountingStorage(
            new InMemoryStorage(), saveGate, ARTIFACT_KEY
        );
        final DedupTestSlice slice = new DedupTestSlice(upstream, storage);
        final ExecutorService pool = Executors.newFixedThreadPool(callers, r -> {
            final Thread t = new Thread(r, "dedup-caller");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<CompletableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                responses.add(CompletableFuture.supplyAsync(() -> slice.response(
                    new RequestLine(RqMethod.GET, ARTIFACT_PATH),
                    Headers.EMPTY,
                    Content.EMPTY
                ).join(), pool));
            }
            // Wait for the leader's save to arrive at the gate. This is the
            // signal that every subsequent caller will coalesce onto the
            // in-flight entry rather than starting a new loader.
            assertThat(
                "leader save must reach the gate within timeout",
                storage.awaitSaveAttempted(5, TimeUnit.SECONDS),
                is(true)
            );
            // Give the rest of the callers time to settle onto the
            // SingleFlight entry. The coalescing window is open until the
            // leader's save completes.
            waitForAttach(callers, Duration.ofSeconds(3));
            // Now release the gate so the leader's save completes.
            saveGate.countDown();
            for (final CompletableFuture<Response> r : responses) {
                final Response resp = r.get(5, TimeUnit.SECONDS);
                assertThat(
                    "every caller must receive a 200",
                    resp.status(),
                    equalTo(RsStatus.OK)
                );
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(
            "cache-write loader must be invoked exactly once for "
                + callers + " concurrent callers sharing the same cache key"
                + " — a count > 1 indicates the SingleFlight coalescer was"
                + " bypassed on the cache-write path",
            storage.saveCount(ARTIFACT_KEY),
            equalTo(1)
        );
    }

    /**
     * Every caller in a coalesced burst must receive a 2xx. Under the SIGNAL
     * protocol they all share the same terminal state, so nobody observes a
     * 500/503 when the single underlying loader completes with SUCCESS.
     */
    @Test
    @Timeout(15)
    void concurrentRequestsAllReceiveSuccessSignal() throws Exception {
        final int callers = 50;
        final byte[] body = "shared body".getBytes();
        final CountDownLatch saveGate = new CountDownLatch(1);
        final Slice upstream = immediateOkUpstream(body);
        final GatedCountingStorage storage = new GatedCountingStorage(
            new InMemoryStorage(), saveGate, ARTIFACT_KEY
        );
        final DedupTestSlice slice = new DedupTestSlice(upstream, storage);
        final ExecutorService pool = Executors.newFixedThreadPool(callers, r -> {
            final Thread t = new Thread(r, "dedup-signal");
            t.setDaemon(true);
            return t;
        });
        final AtomicInteger successes = new AtomicInteger(0);
        try {
            final List<CompletableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                responses.add(CompletableFuture.supplyAsync(() -> slice.response(
                    new RequestLine(RqMethod.GET, ARTIFACT_PATH),
                    Headers.EMPTY,
                    Content.EMPTY
                ).join(), pool));
            }
            assertThat(
                storage.awaitSaveAttempted(5, TimeUnit.SECONDS),
                is(true)
            );
            waitForAttach(callers, Duration.ofSeconds(3));
            saveGate.countDown();
            for (final CompletableFuture<Response> r : responses) {
                final Response resp = r.get(5, TimeUnit.SECONDS);
                if (resp.status() == RsStatus.OK) {
                    successes.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(
            "every caller must observe the SUCCESS signal",
            successes.get(),
            equalTo(callers)
        );
    }

    /**
     * Independent keys must NOT be coalesced: N concurrent requests for N
     * distinct paths produce exactly N primary-key saves.
     */
    @Test
    @Timeout(10)
    void distinctKeysAreNotCoalesced() throws Exception {
        final int keys = 8;
        final Slice upstream = immediateOkUpstream("body".getBytes());
        final CountingStorage storage = new CountingStorage(new InMemoryStorage());
        final DedupTestSlice slice = new DedupTestSlice(upstream, storage);
        final ExecutorService pool = Executors.newFixedThreadPool(keys, r -> {
            final Thread t = new Thread(r, "dedup-distinct");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<CompletableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < keys; i++) {
                final String path = "/com/example/foo/1.0/foo-1.0-" + i + ".jar";
                responses.add(CompletableFuture.supplyAsync(() -> slice.response(
                    new RequestLine(RqMethod.GET, path),
                    Headers.EMPTY,
                    Content.EMPTY
                ).join(), pool));
            }
            for (final CompletableFuture<Response> r : responses) {
                final Response resp = r.get(5, TimeUnit.SECONDS);
                assertThat(resp.status(), equalTo(RsStatus.OK));
            }
        } finally {
            pool.shutdownNow();
        }
        int totalSaves = 0;
        for (int i = 0; i < keys; i++) {
            final Key key = new Key.From("com/example/foo/1.0/foo-1.0-" + i + ".jar");
            final int saves = storage.saveCount(key);
            assertThat(
                "each distinct key must be written at least once",
                saves,
                greaterThanOrEqualTo(1)
            );
            totalSaves += saves;
        }
        // Absolute bound: each key can generate at most one save (no duplicate
        // writes for the same key within one coalesced burst). The total must
        // not exceed one per distinct key.
        assertThat(
            "distinct keys must not cross-coalesce or duplicate-write",
            totalSaves,
            equalTo(keys)
        );
    }

    /**
     * Fresh-after-complete: once the leader's loader completes and the
     * SingleFlight entry is invalidated, a subsequent burst for the same key
     * must hit the cache and skip the loader entirely. This guards the
     * invariant that the coalescer holds in-flight state only, never results.
     */
    @Test
    @Timeout(10)
    void cacheHitAfterCoalescedFetchSkipsLoader() throws Exception {
        final byte[] body = "cache hit body".getBytes();
        final Slice upstream = immediateOkUpstream(body);
        final CountingStorage storage = new CountingStorage(new InMemoryStorage());
        final DedupTestSlice slice = new DedupTestSlice(upstream, storage);
        // Prime the cache with a single request.
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);
        assertThat(first.status(), equalTo(RsStatus.OK));
        final int primed = storage.saveCount(ARTIFACT_KEY);
        assertThat("first request writes the cache exactly once", primed, equalTo(1));
        // Second burst — all cache hits, no new writes.
        final int callers = 32;
        final ExecutorService pool = Executors.newFixedThreadPool(callers, r -> {
            final Thread t = new Thread(r, "dedup-cache-hit");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<CompletableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                responses.add(CompletableFuture.supplyAsync(() -> slice.response(
                    new RequestLine(RqMethod.GET, ARTIFACT_PATH),
                    Headers.EMPTY,
                    Content.EMPTY
                ).join(), pool));
            }
            for (final CompletableFuture<Response> r : responses) {
                final Response resp = r.get(5, TimeUnit.SECONDS);
                assertThat(resp.status(), equalTo(RsStatus.OK));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(
            "cache-hit follow-ups must not trigger additional loader"
                + " invocations",
            storage.saveCount(ARTIFACT_KEY),
            equalTo(primed)
        );
    }

    /**
     * Sleep long enough for every caller to have attached to the SingleFlight
     * entry after the leader's save has reached the gate. 25 ms per caller
     * is empirically comfortable on a test JVM — the leader's save is
     * gated, so no caller can complete until we explicitly release; the
     * only risk is the executor starving a caller, and the pool is sized
     * to cover every caller with its own thread.
     */
    private static void waitForAttach(final int callers, final Duration maxWait)
        throws InterruptedException {
        final long settle = Math.min(
            maxWait.toMillis(),
            Math.max(100L, 25L * callers)
        );
        Thread.sleep(settle);
    }

    /**
     * Build an upstream slice that answers a 200 with {@code body}
     * immediately.
     */
    private static Slice immediateOkUpstream(final byte[] body) {
        return (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/java-archive")
                .body(body)
                .build()
        );
    }

    /**
     * Minimal {@link BaseCachedProxySlice} subclass. All paths are cacheable
     * and storage-backed so requests flow through {@code fetchAndCache} where
     * the SingleFlight coalescer lives.
     */
    private static final class DedupTestSlice extends BaseCachedProxySlice {

        DedupTestSlice(final Slice upstream, final Storage storage) {
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

    /**
     * Baseline {@link Storage} wrapper that counts {@code save} calls per
     * key. Thread-safe.
     */
    private static class CountingStorage extends Storage.Wrap {

        /**
         * Per-key save-call counter, indexed by {@link Key#string()}.
         */
        private final ConcurrentMap<String, AtomicInteger> counts =
            new ConcurrentHashMap<>();

        CountingStorage(final Storage delegate) {
            super(delegate);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.counts.computeIfAbsent(key.string(), k -> new AtomicInteger(0))
                .incrementAndGet();
            return super.save(key, content);
        }

        /**
         * Number of {@code save} invocations observed for {@code key}.
         *
         * @param key Key to count.
         * @return Count (0 if never saved).
         */
        int saveCount(final Key key) {
            final AtomicInteger c = this.counts.get(key.string());
            return c == null ? 0 : c.get();
        }
    }

    /**
     * {@link CountingStorage} variant that gates {@code save} on a latch
     * <em>for a specific key</em>. Used to keep the coalescing window open
     * for the full test.
     */
    private static final class GatedCountingStorage extends CountingStorage {

        /**
         * Latch that gates {@link #save} for the watched key.
         */
        private final CountDownLatch gate;

        /**
         * Key whose save is gated. Other keys pass through unmodified.
         */
        private final Key watched;

        /**
         * Latch that fires when the first {@code save} for the watched key
         * is observed, so the test can synchronize on "leader has arrived".
         */
        private final CountDownLatch attempted = new CountDownLatch(1);

        GatedCountingStorage(
            final Storage delegate,
            final CountDownLatch gate,
            final Key watched
        ) {
            super(delegate);
            this.gate = gate;
            this.watched = watched;
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            if (!key.string().equals(this.watched.string())) {
                return super.save(key, content);
            }
            this.attempted.countDown();
            return CompletableFuture.runAsync(() -> {
                try {
                    this.gate.await();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }).thenCompose(v -> super.save(key, content));
        }

        /**
         * Wait until the first {@code save} for the watched key has been
         * attempted.
         *
         * @param timeout Maximum wait.
         * @param unit    Unit of the wait.
         * @return True if a save was attempted within the timeout.
         * @throws InterruptedException if interrupted.
         */
        boolean awaitSaveAttempted(final long timeout, final TimeUnit unit)
            throws InterruptedException {
            return this.attempted.await(timeout, unit);
        }
    }
}
