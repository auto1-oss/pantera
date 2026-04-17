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
package com.auto1.pantera.cooldown.metadata;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests stale-while-revalidate behaviour on FilteredMetadataCache (H3).
 *
 * On TTL expiry the cache should:
 * 1. Return stale bytes immediately (no blocking wait).
 * 2. Trigger background re-evaluation.
 * 3. On subsequent get(), return fresh bytes once revalidation completes.
 *
 * @since 2.2.0
 */
final class FilteredMetadataCacheSWRTest {

    @Test
    void returnsStaleBytesThenFreshAfterRevalidation() throws Exception {
        // Build cache with a generous Caffeine maxSize / TTL so entries live
        // long enough in the underlying Caffeine cache for our test to read them.
        // The dynamic CacheEntry.isExpired() check drives SWR, not Caffeine eviction.
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            1_000, Duration.ofHours(1), Duration.ofHours(1), null
        );

        final byte[] staleData = "stale-metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] freshData = "fresh-metadata".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger loadCount = new AtomicInteger(0);
        final CountDownLatch revalidationStarted = new CountDownLatch(1);

        // Step 1: Populate cache with an entry that will expire in 100 ms
        cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        staleData,
                        Instant.now().plus(Duration.ofMillis(100)),
                        Duration.ofHours(1)
                    )
                );
            }
        ).get();

        assertThat("Initial load should fire", loadCount.get(), equalTo(1));

        // Step 2: Wait for the entry to expire (blockedUntil has passed)
        Thread.sleep(150);

        // Step 3: Next get() should return STALE bytes immediately
        // and trigger background revalidation
        final byte[] swrResult = cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                revalidationStarted.countDown();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        freshData, Duration.ofHours(1)
                    )
                );
            }
        ).get();

        assertThat(
            "SWR should return stale bytes immediately",
            swrResult, equalTo(staleData)
        );

        // Step 4: Wait for background revalidation to complete
        final boolean started = revalidationStarted.await(2, TimeUnit.SECONDS);
        assertThat("Background revalidation should fire", started, equalTo(true));

        // Give the background future a moment to write back to cache
        Thread.sleep(50);

        // Step 5: Subsequent get() should return FRESH bytes
        final byte[] freshResult = cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        "should-not-be-used".getBytes(StandardCharsets.UTF_8),
                        Duration.ofHours(1)
                    )
                );
            }
        ).get();

        assertThat(
            "After revalidation, should return fresh bytes",
            freshResult, equalTo(freshData)
        );

        // Total loads: 1 initial + 1 background revalidation = 2
        // (the 3rd get() should be a cache hit, not a load)
        assertThat("Total loads should be 2 (initial + revalidation)", loadCount.get(), equalTo(2));
    }

    @Test
    void doesNotDuplicateRevalidation() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            1_000, Duration.ofHours(1), Duration.ofHours(1), null
        );

        final byte[] staleData = "stale".getBytes(StandardCharsets.UTF_8);
        final byte[] freshData = "fresh".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger loadCount = new AtomicInteger(0);
        final CountDownLatch loaderGate = new CountDownLatch(1);

        // Populate with short-lived entry
        cache.get("npm", "repo", "pkg2",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        staleData,
                        Instant.now().plus(Duration.ofMillis(100)),
                        Duration.ofHours(1)
                    )
                );
            }
        ).get();

        assertThat(loadCount.get(), equalTo(1));

        // Wait for expiry
        Thread.sleep(150);

        // Use a slow loader to simulate async revalidation.
        // The first call returns stale + starts background reload.
        // The second call (while reload is in-flight) should also return stale
        // and NOT start a duplicate reload.
        final CompletableFuture<byte[]> swr1 = cache.get("npm", "repo", "pkg2",
            () -> {
                loadCount.incrementAndGet();
                // Slow loader: waits for gate
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        loaderGate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        freshData, Duration.ofHours(1)
                    );
                });
            }
        );

        // First call returns stale immediately
        assertThat("First SWR call returns stale", swr1.get(), equalTo(staleData));

        // Second call while revalidation is in-flight
        final CompletableFuture<byte[]> swr2 = cache.get("npm", "repo", "pkg2",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(freshData, Duration.ofHours(1))
                );
            }
        );

        // Second call also returns stale (background still running)
        assertThat("Second SWR call returns stale", swr2.get(), equalTo(staleData));

        // Release the background loader
        loaderGate.countDown();

        // Wait for background completion
        Thread.sleep(100);

        // Only 2 loads total: 1 initial + 1 revalidation (no duplicate)
        assertThat("Only one background revalidation should fire", loadCount.get(), equalTo(2));
    }
}
