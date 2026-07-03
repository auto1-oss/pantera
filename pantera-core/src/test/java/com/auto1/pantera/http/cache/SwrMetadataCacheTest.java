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

import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit tests for {@link SwrMetadataCache}: verifies the three-state TTL contract
 * (fresh / soft-stale / hard-stale) plus single-flight refresh dedup.
 *
 * @since 2.2.0
 */
final class SwrMetadataCacheTest {

    @Test
    @DisplayName("fresh hit within soft TTL does not invoke loader")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void freshHit_doesNotInvokeLoader() throws Exception {
        final SwrMetadataCache<String, String> cache = new SwrMetadataCache<>(
            Duration.ofSeconds(10), Duration.ofMinutes(1), 100
        );
        final AtomicInteger loaderCalls = new AtomicInteger();
        // Prime the cache once.
        cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v1"));
        }).get(2, TimeUnit.SECONDS);
        assertThat(
            "priming call must invoke the loader exactly once",
            loaderCalls.get(),
            new IsEqual<>(1)
        );

        // Read within the fresh window: loader must NOT be invoked again.
        final Optional<String> second = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v2"));
        }).get(2, TimeUnit.SECONDS);

        assertThat(
            "fresh read must return the originally cached value",
            second,
            new IsEqual<>(Optional.of("v1"))
        );
        assertThat(
            "fresh read must not increment the loader counter",
            loaderCalls.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    @DisplayName("soft-stale hit serves cached value and fires one background refresh")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void softStaleHit_servesCachedBytes_andFiresBackgroundLoader() throws Exception {
        // softTtl=1ms means the prime call sleeps past the soft boundary almost
        // instantly; hardTtl=1m keeps us inside the soft-stale window.
        final SwrMetadataCache<String, String> cache = new SwrMetadataCache<>(
            Duration.ofMillis(1), Duration.ofMinutes(1), 100
        );
        final AtomicInteger loaderCalls = new AtomicInteger();
        final CountDownLatch backgroundDone = new CountDownLatch(1);

        // Prime with the initial value (loader call #1).
        cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v1"));
        }).get(2, TimeUnit.SECONDS);

        // Sleep past softTtl so the next read sees a soft-stale entry.
        Thread.sleep(10L);

        // Soft-stale read: must return cached "v1" immediately and trigger a background
        // refresh that produces "v2". Loader call #2 fires asynchronously.
        final Optional<String> stale = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture
                .completedFuture(Optional.of("v2"))
                .whenComplete((value, error) -> backgroundDone.countDown());
        }).get(2, TimeUnit.SECONDS);

        assertThat(
            "soft-stale read must serve the originally cached value immediately",
            stale,
            new IsEqual<>(Optional.of("v1"))
        );

        // Wait for the background refresh to land.
        assertThat(
            "background refresh must complete within timeout",
            backgroundDone.await(2, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );

        // The loader was invoked exactly twice: once at prime, once for the background
        // refresh. The soft-stale read itself does not block on the loader.
        assertThat(
            "loader must run exactly twice (prime + background refresh)",
            loaderCalls.get(),
            new IsEqual<>(2)
        );

        // After the background refresh, a subsequent fresh read must see v2.
        // Give Caffeine a moment to publish the put, then read.
        Thread.sleep(20L);
        final Optional<String> postRefresh = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v3"));
        }).get(2, TimeUnit.SECONDS);
        assertThat(
            "after background refresh, cache must hold the refreshed value",
            postRefresh,
            new IsEqual<>(Optional.of("v2"))
        );
    }

    @Test
    @DisplayName("hard-stale hit awaits the loader and returns its value")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void hardStaleHit_awaitsLoader() throws Exception {
        // softTtl == hardTtl == 1ms: any subsequent read crosses both boundaries and
        // must go down the synchronous-miss path.
        final SwrMetadataCache<String, String> cache = new SwrMetadataCache<>(
            Duration.ofMillis(1), Duration.ofMillis(1), 100
        );
        final AtomicInteger loaderCalls = new AtomicInteger();

        // Prime.
        cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v1"));
        }).get(2, TimeUnit.SECONDS);

        // Sleep past hardTtl.
        Thread.sleep(20L);

        // Hard-stale read: must AWAIT the loader and return its value.
        final Optional<String> reloaded = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v2"));
        }).get(2, TimeUnit.SECONDS);

        assertThat(
            "hard-stale read must return the loader's value, not the stale one",
            reloaded,
            new IsEqual<>(Optional.of("v2"))
        );
        assertThat(
            "loader must have run twice (prime + hard-stale miss)",
            loaderCalls.get(),
            new IsEqual<>(2)
        );
        // Sanity-check: a v1 result would indicate the stale path was taken.
        assertThat(
            "hard-stale path must not serve the stale value",
            reloaded,
            new IsNot<>(new IsEqual<>(Optional.of("v1")))
        );
    }

    @Test
    @DisplayName("concurrent soft-stale calls dedup to a single loader invocation")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void dedup_singleRefreshUnderConcurrentSoftStaleCalls() throws Exception {
        final SwrMetadataCache<String, String> cache = new SwrMetadataCache<>(
            Duration.ofMillis(1), Duration.ofMinutes(1), 100
        );
        final AtomicInteger primeCalls = new AtomicInteger();
        final AtomicInteger refreshCalls = new AtomicInteger();
        final CountDownLatch leaderEntered = new CountDownLatch(1);
        final CountDownLatch leaderRelease = new CountDownLatch(1);

        // Prime.
        cache.get("k", () -> {
            primeCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v1"));
        }).get(2, TimeUnit.SECONDS);

        // Cross the soft boundary.
        Thread.sleep(10L);

        // 10 concurrent soft-stale reads. The leader's loader future is held until we
        // explicitly release it, so every caller arrives while the refresh is still in
        // flight — which is the only configuration that can prove dedup (a fast loader
        // would let later callers see the entry already refreshed and the slot already
        // freed, yielding a false negative).
        final int callers = 10;
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            final CountDownLatch allDone = new CountDownLatch(callers);
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        cache.get("k", () -> {
                            refreshCalls.incrementAndGet();
                            leaderEntered.countDown();
                            // Block until the test releases the loader.
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    leaderRelease.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                                return Optional.of("v2");
                            });
                        }).get(5, TimeUnit.SECONDS);
                    } catch (final Exception ex) {
                        // Soft-stale reads return immediately; no caller should
                        // synchronously fail here.
                        throw new IllegalStateException("soft-stale caller failed", ex);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            // Wait until the leader's loader has actually been entered, then for all
            // soft-stale reads to return their cached value.
            assertThat(
                "leader loader must have been invoked",
                leaderEntered.await(2, TimeUnit.SECONDS),
                new IsEqual<>(true)
            );
            assertThat(
                "all soft-stale callers must return without awaiting the loader",
                allDone.await(2, TimeUnit.SECONDS),
                new IsEqual<>(true)
            );

            // While the leader is still in flight, the refresh counter must be exactly
            // 1 — proving dedup. Releasing the leader afterwards lets the test exit
            // cleanly.
            assertThat(
                "concurrent soft-stale reads must dedup to a single loader invocation",
                refreshCalls.get(),
                new IsEqual<>(1)
            );
            leaderRelease.countDown();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(
            "prime path ran exactly once",
            primeCalls.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    @DisplayName("absent key loads upstream once and subsequent fresh reads hit cache")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void absent_loaderResultIsCached_andSubsequentReadIsFresh() throws Exception {
        final SwrMetadataCache<String, String> cache = new SwrMetadataCache<>(
            Duration.ofSeconds(10), Duration.ofMinutes(1), 100
        );
        final AtomicInteger loaderCalls = new AtomicInteger();

        // First read: absent, loader is invoked and its result is cached.
        final Optional<String> first = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v1"));
        }).get(2, TimeUnit.SECONDS);
        assertThat(
            "first read must return the loader's value",
            first,
            new IsEqual<>(Optional.of("v1"))
        );
        assertThat(
            "first read must invoke the loader exactly once",
            loaderCalls.get(),
            new IsEqual<>(1)
        );

        // Second read within softTtl: fresh hit, loader must not be invoked.
        final Optional<String> second = cache.get("k", () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("v2"));
        }).get(2, TimeUnit.SECONDS);
        assertThat(
            "second fresh read must return the originally cached value",
            second,
            new IsEqual<>(Optional.of("v1"))
        );
        assertThat(
            "second fresh read must not re-invoke the loader",
            loaderCalls.get(),
            new IsEqual<>(1)
        );
        assertThat(
            "cache size must reflect the single cached entry",
            cache.size(),
            new IsEqual<>(1L)
        );
    }
}
