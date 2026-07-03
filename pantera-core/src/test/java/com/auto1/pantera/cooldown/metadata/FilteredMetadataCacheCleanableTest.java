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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for the {@link FilteredMetadataCache#invalidate(String)} /
 * {@link FilteredMetadataCache#invalidateAll()} Cleanable implementation
 * added for cross-instance pub/sub fan-out. These are the receive-side
 * handlers: they must drop the local L1 entry without touching L2 and
 * without re-publishing (re-publish would create an invalidation loop;
 * L2 was already cleared by the originator).
 *
 * @since 2.2.0
 */
final class FilteredMetadataCacheCleanableTest {

    private FilteredMetadataCache cache;

    @BeforeEach
    void setUp() {
        // L1-only (no Valkey) keeps the test focused on the in-memory tier
        this.cache = new FilteredMetadataCache();
    }

    @Test
    void invalidateByFullKeyDropsL1Entry() throws Exception {
        final byte[] payload = "metadata-bytes".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger loaderCalls = new AtomicInteger(0);
        // Seed L1 via the public get() API
        this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        assertThat("L1 must be populated on first miss", loaderCalls.get(), equalTo(1));
        // Pre-condition: second get is a hit
        this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        assertThat("second get must hit L1 (loader unchanged)", loaderCalls.get(), equalTo(1));
        // Act: receive-side invalidation via the new Cleanable method
        final String key = FilteredMetadataCache.cacheKey("npm", "test-repo", "lodash");
        this.cache.invalidate(key);
        // Post-condition: next get misses L1 → loader fires again
        this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        assertThat("L1 miss after invalidate must call loader again", loaderCalls.get(), equalTo(2));
    }

    @Test
    void invalidateAllDropsAllL1Entries() throws Exception {
        final byte[] payload = "metadata-bytes".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger loaderCalls = new AtomicInteger(0);
        // Seed two L1 entries (different packages)
        this.cache.get("npm", "test-repo", "lodash",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        this.cache.get("npm", "test-repo", "axios",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        assertThat("both entries populated", loaderCalls.get(), equalTo(2));
        // Act: bulk drop via the new Cleanable method
        this.cache.invalidateAll();
        // Post-condition: both packages miss L1 → loader fires twice more
        this.cache.get("npm", "test-repo", "lodash",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        this.cache.get("npm", "test-repo", "axios",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(payload, Duration.ofHours(24))
                );
            }
        ).get();
        assertThat(
            "both L1 entries must be gone after invalidateAll",
            loaderCalls.get(), equalTo(4)
        );
    }
}
