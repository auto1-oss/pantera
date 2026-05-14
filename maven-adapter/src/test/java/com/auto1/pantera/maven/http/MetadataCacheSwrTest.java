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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.maven.http.MetadataCache.MetadataFetchResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.number.OrderingComparison;
import org.junit.jupiter.api.Test;

/**
 * Tests for T-P11 stale-while-revalidate semantics in {@link MetadataCache}.
 *
 * <p>Acceptance criteria from {@code analysis/plan/v2/IMPLEMENTATION.md}:
 * <ul>
 *   <li>After the first miss, every subsequent metadata request within
 *       the soft TTL serves in ≤ 5 ms (no upstream call).</li>
 *   <li>Between soft and hard TTL: serves in ≤ 5 ms AND triggers a
 *       background refresh visible in upstream metrics.</li>
 *   <li>After hard TTL: blocks on upstream.</li>
 *   <li>No more than one background refresh per {@code (repo, metadata-key)}
 *       per soft-TTL window — verified by single-flight test.</li>
 * </ul>
 */
final class MetadataCacheSwrTest {

    private static final Key KEY = new Key.From("org/example/foo/maven-metadata.xml");

    private static final byte[] BODY = "<m/>".getBytes(StandardCharsets.UTF_8);

    /**
     * Acceptance: within soft TTL, no upstream call and the response is
     * fast.
     */
    @Test
    void freshWindowServesFromCacheWithoutUpstream() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final AtomicInteger calls = new AtomicInteger();
        final MetadataCache cache = newCache(clock, Duration.ofSeconds(30), Duration.ofHours(2));
        // Cold miss seeds the cache.
        cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(BODY, "\"v1\"", "lm-1")
            );
        }).join();
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(1));
        // Advance within the soft TTL: 10 calls, no upstream.
        clock.advance(Duration.ofSeconds(10));
        for (int idx = 0; idx < 10; idx++) {
            final long start = System.nanoTime();
            final Optional<Content> served = cache.load(KEY, req -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    MetadataFetchResult.modified(BODY, "\"v2\"", "lm-2")
                );
            }).join();
            final long elapsed = (System.nanoTime() - start) / 1_000_000L;
            MatcherAssert.assertThat(
                "Fresh-window serve must be ≤ 5 ms (was " + elapsed + " ms)",
                elapsed, OrderingComparison.lessThanOrEqualTo(5L)
            );
            MatcherAssert.assertThat(
                "Fresh-window serve must return cached content",
                served.isPresent(), new IsEqual<>(true)
            );
        }
        MatcherAssert.assertThat(
            "No upstream calls inside the soft TTL window",
            calls.get(), new IsEqual<>(1)
        );
    }

    /**
     * Acceptance: between soft and hard TTL, the foreground response is
     * cached/fast AND a background refresh fires.
     */
    @Test
    void staleWindowServesCachedAndFiresBackgroundRefresh() throws Exception {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch refreshDone = new CountDownLatch(1);
        final MetadataCache cache = newCache(clock, Duration.ofMillis(50), Duration.ofMinutes(10));
        cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(BODY, "\"v1\"", "lm-1")
            );
        }).join();
        // Move past softTtl into the stale window.
        clock.advance(Duration.ofMillis(100));
        final long start = System.nanoTime();
        final Optional<Content> served = cache.load(KEY, req -> {
            // This loader is the background refresh.
            calls.incrementAndGet();
            refreshDone.countDown();
            return CompletableFuture.completedFuture(MetadataFetchResult.unmodified());
        }).join();
        final long elapsed = (System.nanoTime() - start) / 1_000_000L;
        MatcherAssert.assertThat(
            "Stale-window foreground response must be ≤ 5 ms",
            elapsed, OrderingComparison.lessThanOrEqualTo(5L)
        );
        MatcherAssert.assertThat(
            "Stale-window response carries cached bytes",
            served.isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Background refresh fires within 2 s",
            refreshDone.await(2, TimeUnit.SECONDS), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Exactly one background refresh observed",
            calls.get(), new IsEqual<>(2)
        );
    }

    /**
     * Acceptance: after hard TTL, the request blocks on upstream rather
     * than serving stale.
     */
    @Test
    void pastHardTtlBlocksOnUpstream() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final AtomicInteger calls = new AtomicInteger();
        final MetadataCache cache = newCache(
            clock, Duration.ofMillis(50), Duration.ofMillis(200)
        );
        cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(BODY, "\"v1\"", "lm-1")
            );
        }).join();
        // Move past hardTtl.
        clock.advance(Duration.ofSeconds(1));
        final AtomicInteger loaderInvocations = new AtomicInteger();
        final Optional<Content> served = cache.load(KEY, req -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(
                    "<refreshed/>".getBytes(StandardCharsets.UTF_8),
                    "\"v2\"", "lm-2"
                )
            );
        }).join();
        MatcherAssert.assertThat(
            "Hard-TTL fall-through invokes the loader synchronously",
            loaderInvocations.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Hard-TTL fall-through returns the refreshed bytes",
            new String(served.get().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("<refreshed/>")
        );
    }

    /**
     * Acceptance: single-flight collapses concurrent stale-window
     * refreshes to one upstream call per (repo, key) per soft-TTL window.
     */
    @Test
    void singleFlightCollapsesConcurrentStaleRefreshes() throws Exception {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = newCache(
            clock, Duration.ofMillis(50), Duration.ofMinutes(10)
        );
        // Seed.
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(BODY, "\"v1\"", "lm-1")
            )
        ).join();
        // Move into the stale window.
        clock.advance(Duration.ofMillis(100));
        final int concurrency = 50;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger refreshes = new AtomicInteger();
        final AtomicReference<CountDownLatch> refreshGate = new AtomicReference<>(
            new CountDownLatch(1)
        );
        final ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            final CountDownLatch done = new CountDownLatch(concurrency);
            for (int idx = 0; idx < concurrency; idx++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        cache.load(KEY, req -> {
                            // Block briefly so concurrent followers really
                            // do attempt to enter the refresh path; the
                            // single-flight must coalesce them.
                            refreshes.incrementAndGet();
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    refreshGate.get().await(2, TimeUnit.SECONDS);
                                } catch (final InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                return MetadataFetchResult.unmodified();
                            });
                        }).join();
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // Release all callers at once.
            start.countDown();
            done.await(5, TimeUnit.SECONDS);
            // Let the single in-flight refresh complete.
            refreshGate.get().countDown();
            // Wait briefly for the refresh to finish.
            Thread.sleep(200);
        } finally {
            pool.shutdownNow();
        }
        MatcherAssert.assertThat(
            "Single-flight must collapse 50 concurrent stale-window refreshes "
                + "into exactly one upstream call (observed: "
                + refreshes.get() + ")",
            refreshes.get(), new IsEqual<>(1)
        );
    }

    /**
     * Acceptance follow-up: after a stale-window refresh completes, the
     * single-flight slot is released so a NEW stale-window window can fire
     * another refresh (one per window, not one ever).
     */
    @Test
    void freshSwrRefreshIsAllowedAfterPreviousCompletes() throws Exception {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final AtomicInteger refreshes = new AtomicInteger();
        final MetadataCache cache = newCache(
            clock, Duration.ofMillis(50), Duration.ofMinutes(10)
        );
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(BODY, "\"v1\"", "lm-1")
            )
        ).join();
        // First stale-window pass.
        clock.advance(Duration.ofMillis(100));
        cache.load(KEY, req -> {
            refreshes.incrementAndGet();
            return CompletableFuture.completedFuture(MetadataFetchResult.unmodified());
        }).join();
        Thread.sleep(100); // let background complete
        MatcherAssert.assertThat(refreshes.get(), new IsEqual<>(1));
        // Now the entry's lastVerified just got bumped — advance again so
        // it's stale again.
        clock.advance(Duration.ofMillis(100));
        cache.load(KEY, req -> {
            refreshes.incrementAndGet();
            return CompletableFuture.completedFuture(MetadataFetchResult.unmodified());
        }).join();
        Thread.sleep(100);
        MatcherAssert.assertThat(
            "After the first SWR refresh completes, a NEW stale window must "
                + "be allowed to fire its own refresh.",
            refreshes.get(), new IsEqual<>(2)
        );
    }

    private static MetadataCache newCache(
        final Clock clock, final Duration soft, final Duration hard
    ) {
        return new MetadataCache(soft, hard, 10_000, null, "swr-test", clock);
    }

    /**
     * Mutable clock for time-travel tests.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        private MutableClock(final Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        static MutableClock at(final Instant initial) {
            return new MutableClock(initial);
        }

        void advance(final Duration delta) {
            this.now.updateAndGet(cur -> cur.plus(delta));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }
}
