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
package com.auto1.pantera.chaos;

import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;

/**
 * Chaos test: Valkey L2 is slow/unreachable — assert the cooldown L1
 * (Caffeine) keeps serving without the calling thread blocking on L2.
 *
 * <p>Scenario: the L2-backing loader (which in production fronts
 * Valkey / Redis) stalls for seconds. A warm L1 entry must short-circuit
 * {@link FilteredMetadataCache#get} so that concurrent readers return
 * immediately and never enter the slow path.
 *
 * <p>Implementation: we warm L1 with a cooldown-filtered metadata
 * response, then flood the cache with 100 concurrent reads of the same
 * key. The loader (stand-in for the slow L2/upstream path) sleeps
 * 2 seconds on every invocation, so if the L1 short-circuit ever
 * slipped we would easily blow the 500 ms wall-clock budget and the
 * loader invocation counter would increment past the single warm-up.
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required
 * (matching the style of sibling chaos tests in this package).
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class CooldownValkeyStalenessTest {

    /**
     * Number of concurrent readers.
     */
    private static final int CONCURRENT = 100;

    /**
     * Simulated slow-L2 delay on every loader invocation.
     * Chosen to be many multiples of any plausible L1 read latency
     * so that a regression (L2 blocking the hot path) is unmissable.
     */
    private static final Duration SLOW_L2_DELAY = Duration.ofSeconds(2);

    /**
     * Wall-clock budget for the warm read burst.
     * L1 (Caffeine) reads are sub-microsecond; 500 ms gives generous
     * headroom for CI jitter and thread wake-up.
     */
    private static final Duration WARM_READ_BUDGET = Duration.ofMillis(500);

    /**
     * Repository type used for cache keys.
     */
    private static final String REPO_TYPE = "go";

    /**
     * Repository name used for cache keys.
     */
    private static final String REPO_NAME = "go-repo";

    /**
     * Package name used for cache keys.
     */
    private static final String PACKAGE = "example.com/stale-pkg";

    /**
     * Cooldown-filtered metadata bytes used as the warm L1 payload.
     */
    private static final byte[] FILTERED_BYTES =
        "v0.1.0\nv0.2.0\nv0.3.0".getBytes(StandardCharsets.UTF_8);

    /**
     * Warm L1, then issue 100 concurrent reads while the L2/loader path
     * is pathologically slow. All reads must return within the L1
     * latency budget — proving L2 never blocks the served thread.
     */
    @Test
    void warmL1_servesUnderSlowL2_withoutBlocking() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            1000, Duration.ofHours(24), Duration.ofHours(24), null
        );

        final AtomicInteger loaderInvocations = new AtomicInteger(0);

        // Warm L1 with a cooldown-filtered metadata response. The loader
        // is invoked exactly once here; from this point on a healthy L1
        // must serve every subsequent read.
        final byte[] warmResult = cache.get(
            REPO_TYPE, REPO_NAME, PACKAGE,
            () -> {
                loaderInvocations.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        FILTERED_BYTES, Duration.ofHours(24)
                    )
                );
            }
        ).get(5, TimeUnit.SECONDS);

        assertThat("Warm-up must return the filtered bytes",
            new String(warmResult, StandardCharsets.UTF_8),
            equalTo(new String(FILTERED_BYTES, StandardCharsets.UTF_8)));
        assertThat("Warm-up must invoke the loader exactly once",
            loaderInvocations.get(), equalTo(1));

        // Now install a pathologically slow loader. If L1 ever fails
        // to short-circuit, the reader thread will be dragged into this
        // 2-second sleep — blowing the 500 ms wall-clock budget and
        // bumping the loader-invocation counter beyond the warm-up.
        final java.util.function.Supplier<CompletableFuture<FilteredMetadataCache.CacheEntry>>
            slowLoader = () -> CompletableFuture.supplyAsync(() -> {
                loaderInvocations.incrementAndGet();
                try {
                    Thread.sleep(SLOW_L2_DELAY.toMillis());
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return FilteredMetadataCache.CacheEntry.noBlockedVersions(
                    FILTERED_BYTES, Duration.ofHours(24)
                );
            });

        final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(CONCURRENT);
        final AtomicLong maxPerThreadNanos = new AtomicLong(0L);

        @SuppressWarnings("unchecked")
        final CompletableFuture<byte[]>[] futures = new CompletableFuture[CONCURRENT];

        for (int i = 0; i < CONCURRENT; i++) {
            final int idx = i;
            final CompletableFuture<byte[]> future = new CompletableFuture<>();
            futures[idx] = future;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    final long tStart = System.nanoTime();
                    final byte[] result = cache.get(
                        REPO_TYPE, REPO_NAME, PACKAGE, slowLoader
                    ).get(10, TimeUnit.SECONDS);
                    final long elapsed = System.nanoTime() - tStart;
                    // Track the slowest observed per-thread read for diagnostics.
                    long prev;
                    do {
                        prev = maxPerThreadNanos.get();
                        if (elapsed <= prev) {
                            break;
                        }
                    } while (!maxPerThreadNanos.compareAndSet(prev, elapsed));
                    future.complete(result);
                } catch (final Exception ex) {
                    future.completeExceptionally(ex);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Fire all readers simultaneously and measure total wall clock.
        final long burstStart = System.nanoTime();
        startGate.countDown();
        final boolean finished = doneGate.await(
            WARM_READ_BUDGET.toMillis() + 2_000, TimeUnit.MILLISECONDS
        );
        final long burstNanos = System.nanoTime() - burstStart;
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat("All 100 concurrent readers must complete", finished, equalTo(true));

        // (a) Latency budget — total wall-clock for the warm-read burst.
        final long burstMillis = burstNanos / 1_000_000L;
        assertThat(
            "Warm-L1 burst must finish within "
                + WARM_READ_BUDGET.toMillis() + "ms (observed "
                + burstMillis + "ms, slowest single read "
                + maxPerThreadNanos.get() / 1_000_000L + "ms). "
                + "Exceeding the budget means the slow L2 path leaked "
                + "into a served thread — L1 is not short-circuiting.",
            burstMillis, lessThan(WARM_READ_BUDGET.toMillis())
        );

        // (b) L2/loader must NOT have been re-entered. Exactly one
        // invocation (the warm-up) proves the 100 concurrent readers
        // were served entirely by L1, never waiting on the slow path.
        assertThat(
            "Loader must not be re-invoked during the warm-read burst. "
                + "Actual invocation count: " + loaderInvocations.get()
                + " (expected 1 for the initial warm-up only).",
            loaderInvocations.get(), equalTo(1)
        );

        // (c) All reads must return the warm payload byte-for-byte.
        for (int i = 0; i < CONCURRENT; i++) {
            final byte[] result = futures[i].get(1, TimeUnit.SECONDS);
            assertThat("Reader " + i + " must receive the warm payload",
                new String(result, StandardCharsets.UTF_8),
                equalTo(new String(FILTERED_BYTES, StandardCharsets.UTF_8)));
        }
    }
}
