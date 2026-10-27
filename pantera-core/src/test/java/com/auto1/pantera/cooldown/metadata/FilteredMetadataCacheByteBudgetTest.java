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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The L1 of {@link FilteredMetadataCache} is bounded by bytes as well as by
 * entry count. Caffeine evicts asynchronously, so both tests poll for the
 * bounded state instead of asserting it right after the inserts.
 *
 * @since 2.2.11
 */
final class FilteredMetadataCacheByteBudgetTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    @Timeout(30)
    void evictsWhenSummedEntryBytesExceedTheBudget() throws Exception {
        final long budget = 1024L * 1024L;
        final FilteredMetadataCache cache = new FilteredMetadataCache(1000, budget, TTL, TTL, null);
        for (int idx = 0; idx < 10; ++idx) {
            FilteredMetadataCacheByteBudgetTest.load(cache, "pkg-" + idx, 256 * 1024);
        }
        MatcherAssert.assertThat(
            FilteredMetadataCacheByteBudgetTest.settles(cache::size, 4L),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(30)
    void keepsTheEntryCountBoundForSmallEntries() throws Exception {
        final FilteredMetadataCache cache =
            new FilteredMetadataCache(3, 1024L * 1024L * 1024L, TTL, TTL, null);
        for (int idx = 0; idx < 10; ++idx) {
            FilteredMetadataCacheByteBudgetTest.load(cache, "pkg-" + idx, 16);
        }
        MatcherAssert.assertThat(
            FilteredMetadataCacheByteBudgetTest.settles(cache::size, 3L),
            new IsEqual<>(true)
        );
    }

    private static void load(final FilteredMetadataCache cache, final String pkg, final int bytes)
        throws Exception {
        cache.get(
            "npm", "npm-proxy", pkg,
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(new byte[bytes], TTL)
            )
        ).get();
    }

    /**
     * Poll until the size drops to {@code max} or below; the test timeout
     * turns a cache that never settles into a failure.
     */
    private static boolean settles(final LongSupplier size, final long max)
        throws InterruptedException {
        while (size.getAsLong() > max) {
            Thread.sleep(20L);
        }
        return true;
    }
}
