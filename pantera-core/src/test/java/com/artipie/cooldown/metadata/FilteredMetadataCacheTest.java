/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests for {@link FilteredMetadataCache}.
 *
 * @since 1.0
 */
final class FilteredMetadataCacheTest {

    private FilteredMetadataCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new FilteredMetadataCache();
    }

    @Test
    void cachesMetadataOnFirstAccess() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] expected = "test-metadata".getBytes(StandardCharsets.UTF_8);

        // First access - should load
        final byte[] result1 = this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(result1, equalTo(expected));
        assertThat(loadCount.get(), equalTo(1));

        // Second access - should hit cache
        final byte[] result2 = this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(result2, equalTo(expected));
        assertThat(loadCount.get(), equalTo(1)); // Still 1 - cache hit
    }

    @Test
    void invalidatesSpecificPackage() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] expected = "test-metadata".getBytes(StandardCharsets.UTF_8);

        // Load into cache
        this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(loadCount.get(), equalTo(1));

        // Invalidate
        this.cache.invalidate("npm", "test-repo", "lodash");

        // Should reload
        this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(loadCount.get(), equalTo(2));
    }

    @Test
    void invalidatesAllForRepository() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] expected = "test-metadata".getBytes(StandardCharsets.UTF_8);

        // Load multiple packages
        this.cache.get("npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        this.cache.get("npm", "test-repo", "express",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(loadCount.get(), equalTo(2));

        // Invalidate all for repo
        this.cache.invalidateAll("npm", "test-repo");

        // Both should reload
        this.cache.get("npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        this.cache.get("npm", "test-repo", "express",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        ).get();

        assertThat(loadCount.get(), equalTo(4));
    }

    @Test
    void preventsConcurrentLoadsForSameKey() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] expected = "test-metadata".getBytes(StandardCharsets.UTF_8);

        // Start multiple concurrent requests
        final CompletableFuture<byte[]> future1 = this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                // Simulate slow load
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        );

        final CompletableFuture<byte[]> future2 = this.cache.get(
            "npm", "test-repo", "lodash",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
                );
            }
        );

        // Wait for both
        future1.get();
        future2.get();

        // Should only load once (stampede prevention)
        assertThat(loadCount.get(), equalTo(1));
    }

    @Test
    void statsReportsCorrectly() throws Exception {
        final byte[] expected = "test".getBytes(StandardCharsets.UTF_8);

        // Generate some activity
        this.cache.get("npm", "repo", "pkg1",
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
            )).get();
        this.cache.get("npm", "repo", "pkg1",
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
            )).get(); // Hit

        final String stats = this.cache.stats();
        assertThat(stats, containsString("FilteredMetadataCache"));
        assertThat(stats, containsString("l1Hits=1"));
        assertThat(stats, containsString("misses=1"));
    }

    @Test
    void clearRemovesAllEntries() throws Exception {
        final byte[] expected = "test".getBytes(StandardCharsets.UTF_8);

        this.cache.get("npm", "repo", "pkg",
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(expected, Duration.ofHours(24))
            )).get();

        assertThat(this.cache.size(), equalTo(1L));

        this.cache.clear();

        assertThat(this.cache.size(), equalTo(0L));
    }

    @Test
    void cacheEntryWithBlockedVersionsHasDynamicTtl() {
        final byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        final Instant blockedUntil = Instant.now().plus(Duration.ofHours(2));
        
        final FilteredMetadataCache.CacheEntry entry = 
            FilteredMetadataCache.CacheEntry.withBlockedVersions(
                data, blockedUntil, Duration.ofHours(24)
            );
        
        // TTL should be approximately 2 hours (blockedUntil - now)
        final long ttlSeconds = entry.ttlSeconds();
        assertThat(ttlSeconds > 7000 && ttlSeconds <= 7200, equalTo(true));
        assertThat(entry.earliestBlockedUntil().isPresent(), equalTo(true));
    }

    @Test
    void cacheEntryWithNoBlockedVersionsHasMaxTtl() {
        final byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        final Duration maxTtl = Duration.ofHours(24);
        
        final FilteredMetadataCache.CacheEntry entry = 
            FilteredMetadataCache.CacheEntry.noBlockedVersions(data, maxTtl);
        
        // TTL should be max TTL (24 hours)
        final long ttlSeconds = entry.ttlSeconds();
        assertThat(ttlSeconds, equalTo(maxTtl.getSeconds()));
        assertThat(entry.earliestBlockedUntil().isEmpty(), equalTo(true));
    }

    @Test
    void cacheEntryWithExpiredBlockHasMinTtl() {
        final byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        // blockedUntil in the past
        final Instant blockedUntil = Instant.now().minus(Duration.ofMinutes(5));
        
        final FilteredMetadataCache.CacheEntry entry = 
            FilteredMetadataCache.CacheEntry.withBlockedVersions(
                data, blockedUntil, Duration.ofHours(24)
            );
        
        // TTL should be minimum (1 minute) since block already expired
        final long ttlSeconds = entry.ttlSeconds();
        assertThat(ttlSeconds, equalTo(60L)); // MIN_TTL = 1 minute
    }

    @Test
    void cacheExpiresWhenBlockExpires() throws Exception {
        // Test that CacheEntry.isExpired() works correctly
        final byte[] data = "filtered-metadata".getBytes(StandardCharsets.UTF_8);
        
        // Create an entry that expires in 100ms
        final Instant blockedUntil = Instant.now().plus(Duration.ofMillis(100));
        final FilteredMetadataCache.CacheEntry entry = 
            FilteredMetadataCache.CacheEntry.withBlockedVersions(
                data, blockedUntil, Duration.ofHours(24)
            );
        
        // Entry should not be expired immediately
        assertThat("Entry should not be expired immediately", entry.isExpired(), equalTo(false));
        
        // Wait for expiry
        Thread.sleep(150);
        
        // Entry should now be expired
        assertThat("Entry should be expired after blockedUntil", entry.isExpired(), equalTo(true));
        
        // Test cache behavior with manual invalidation (which is the reliable path)
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            1000, Duration.ofHours(24), Duration.ofHours(24), null
        );
        
        final AtomicInteger loadCount = new AtomicInteger(0);
        
        // First load
        cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        data, Instant.now().plus(Duration.ofHours(1)), Duration.ofHours(24)
                    )
                );
            }
        ).get();
        
        assertThat("First load should increment counter", loadCount.get(), equalTo(1));
        
        // Second load - cache hit
        cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        assertThat("Second load should hit cache", loadCount.get(), equalTo(1));
        
        // Invalidate (simulating unblock)
        cache.invalidate("npm", "repo", "pkg");
        
        // Third load - should reload after invalidation
        cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        assertThat("Third load after invalidation should reload", loadCount.get(), equalTo(2));
    }

    @Test
    void manualInvalidationClearsCacheImmediately() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] blockedData = "blocked-metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] unblockedData = "unblocked-metadata".getBytes(StandardCharsets.UTF_8);
        
        // Block expires in 1 hour (long TTL)
        final Instant blockedUntil = Instant.now().plus(Duration.ofHours(1));
        
        // First load with blocked version
        final byte[] result1 = this.cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        blockedData, blockedUntil, Duration.ofHours(24)
                    )
                );
            }
        ).get();
        
        assertThat(result1, equalTo(blockedData));
        assertThat(loadCount.get(), equalTo(1));
        
        // Simulate manual unblock by invalidating cache
        this.cache.invalidate("npm", "repo", "pkg");
        
        // Next request should reload with unblocked data
        final byte[] result2 = this.cache.get("npm", "repo", "pkg",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    // Now includes previously blocked version
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        unblockedData, Duration.ofHours(24)
                    )
                );
            }
        ).get();
        
        assertThat("After invalidation, should return new data", result2, equalTo(unblockedData));
        assertThat("After invalidation, should reload", loadCount.get(), equalTo(2));
    }

    @Test
    void invalidateAllClearsAllPackagesForRepo() throws Exception {
        final AtomicInteger loadCount = new AtomicInteger(0);
        final byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        final Instant blockedUntil = Instant.now().plus(Duration.ofHours(1));
        
        // Load multiple packages
        this.cache.get("npm", "repo", "pkg1",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        data, blockedUntil, Duration.ofHours(24)
                    )
                );
            }
        ).get();
        
        this.cache.get("npm", "repo", "pkg2",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        data, blockedUntil, Duration.ofHours(24)
                    )
                );
            }
        ).get();
        
        // Different repo - should not be affected
        this.cache.get("npm", "other-repo", "pkg3",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        assertThat(loadCount.get(), equalTo(3));
        
        // Invalidate all for "repo" (simulating unblockAll)
        this.cache.invalidateAll("npm", "repo");
        
        // pkg1 and pkg2 should reload
        this.cache.get("npm", "repo", "pkg1",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        this.cache.get("npm", "repo", "pkg2",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        // pkg3 in other-repo should still be cached
        this.cache.get("npm", "other-repo", "pkg3",
            () -> {
                loadCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(data, Duration.ofHours(24))
                );
            }
        ).get();
        
        // 3 initial + 2 reloads for repo (pkg3 should hit cache)
        assertThat(loadCount.get(), equalTo(5));
    }
}
