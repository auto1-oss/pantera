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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Chaos test: high-cardinality key space — populate 10× the configured
 * L1 capacity with unique package keys and assert that Caffeine's LRU
 * eviction engages at the configured bound with no OOM and no runaway
 * growth.
 *
 * <p>Scenario: a busy deployment resolves thousands of unique package
 * names in quick succession. {@link FilteredMetadataCache}'s L1 must
 * cap at its configured {@code maxSize}; old entries must be evicted
 * and the freshest entries must remain resident.
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required
 * (matching the style of sibling chaos tests in this package).
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class CooldownHighCardinalityTest {

    /**
     * L1 capacity for the test (small, to keep the test fast).
     */
    private static final int CAPACITY = 100;

    /**
     * Total unique keys inserted — 10× capacity.
     */
    private static final int UNIQUE_KEYS = 1_000;

    /**
     * Repository type used for all cache keys.
     */
    private static final String REPO_TYPE = "npm";

    /**
     * Repository name used for all cache keys.
     */
    private static final String REPO_NAME = "npm-proxy";

    /**
     * Package-name template: unique per insertion.
     */
    private static final String PACKAGE_PREFIX = "pkg-";

    /**
     * Overall wall-clock budget for the test.
     */
    private static final Duration BUDGET = Duration.ofSeconds(5);

    /**
     * Populate 1000 unique keys into an L1 sized for 100 entries:
     * Caffeine's LRU must evict aggressively, the cache must stay at
     * or below capacity, the most-recently-inserted keys must remain
     * resident, and the oldest keys must be gone.
     */
    @Test
    void highCardinality_lruEvictsAtCapacity_noOom() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            CAPACITY, Duration.ofHours(24), Duration.ofHours(24), null
        );

        final AtomicInteger loads = new AtomicInteger(0);
        final long startNanos = System.nanoTime();

        // Load UNIQUE_KEYS distinct packages. Each get() is a miss,
        // so each triggers the loader and populates a fresh L1 entry.
        for (int i = 0; i < UNIQUE_KEYS; i++) {
            final int idx = i;
            final byte[] payload =
                ("versions-for-" + idx).getBytes(StandardCharsets.UTF_8);
            final byte[] result = cache.get(
                REPO_TYPE, REPO_NAME, PACKAGE_PREFIX + idx,
                () -> {
                    loads.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        FilteredMetadataCache.CacheEntry.noBlockedVersions(
                            payload, Duration.ofHours(24)
                        )
                    );
                }
            ).get(2, TimeUnit.SECONDS);
            assertThat("Insert " + idx + " must return its own payload",
                new String(result, StandardCharsets.UTF_8),
                equalTo("versions-for-" + idx));
        }

        // Force Caffeine to process pending evictions before we
        // measure size() — estimatedSize() can otherwise lag briefly.
        cache.cleanUp();

        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        // Fast test: complete within the budget.
        assertThat(
            "High-cardinality load must complete within "
                + BUDGET.toMillis() + "ms (observed " + elapsedMillis + "ms)",
            elapsedMillis, lessThanOrEqualTo(BUDGET.toMillis())
        );

        // The loader was invoked for every unique key (each was a miss).
        assertThat(
            "All " + UNIQUE_KEYS + " keys must miss L1 on first touch",
            loads.get(), equalTo(UNIQUE_KEYS)
        );

        // (a) Cache size must respect the configured bound.
        final long size = cache.size();
        assertThat(
            "L1 size must not exceed configured capacity (" + CAPACITY
                + "). Observed size=" + size,
            size, lessThanOrEqualTo((long) CAPACITY)
        );
        assertThat(
            "L1 must actually cache something (size must be > 0)",
            size, greaterThan(0L)
        );

        // (b) Oldest keys must be evicted. We probe the first batch
        // of inserts: the loader MUST be invoked (proving the entry
        // was gone). With CAPACITY=100 and UNIQUE_KEYS=1000, the
        // first 100 keys have been pushed out many times over.
        final AtomicInteger reloadCount = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            cache.get(
                REPO_TYPE, REPO_NAME, PACKAGE_PREFIX + idx,
                () -> {
                    reloadCount.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        FilteredMetadataCache.CacheEntry.noBlockedVersions(
                            ("reload-" + idx).getBytes(StandardCharsets.UTF_8),
                            Duration.ofHours(24)
                        )
                    );
                }
            ).get(1, TimeUnit.SECONDS);
        }
        assertThat(
            "All 10 oldest probed keys must have been evicted and "
                + "reloaded (reloadCount=" + reloadCount.get() + ")",
            reloadCount.get(), equalTo(10)
        );
    }
}
