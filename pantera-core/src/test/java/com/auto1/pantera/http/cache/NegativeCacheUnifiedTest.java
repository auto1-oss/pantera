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

import com.auto1.pantera.cache.NegativeCacheConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the unified {@link NegativeCache} with {@link NegativeCacheKey} API.
 *
 * @since 2.2.0
 */
final class NegativeCacheUnifiedTest {

    private NegativeCache cache;

    @BeforeEach
    void setUp() {
        // Use a config-based constructor to create a single instance
        final NegativeCacheConfig config = new NegativeCacheConfig(
            Duration.ofHours(1), 50_000, false,
            5_000, Duration.ofMinutes(5),
            5_000_000, Duration.ofDays(7)
        );
        this.cache = new NegativeCache(config);
    }

    @Test
    void isKnown404ReturnsFalseForUnknownKey() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "maven-central", "maven", "com.example:foo", "1.0.0"
        );
        assertFalse(cache.isKnown404(key));
    }

    @Test
    void cacheNotFoundThenIsKnown404ReturnsTrue() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "maven-central", "maven", "com.example:foo", "1.0.0"
        );
        cache.cacheNotFound(key);
        assertTrue(cache.isKnown404(key));
    }

    @Test
    void invalidateClearsEntry() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "npm-proxy", "npm", "@scope/bar", "2.0.0"
        );
        cache.cacheNotFound(key);
        assertTrue(cache.isKnown404(key));
        cache.invalidate(key);
        assertFalse(cache.isKnown404(key));
    }

    @Test
    void invalidateBatchClearsMultipleEntries() {
        final NegativeCacheKey k1 = new NegativeCacheKey(
            "pypi-group", "pypi", "requests", "2.28.0"
        );
        final NegativeCacheKey k2 = new NegativeCacheKey(
            "pypi-group", "pypi", "flask", "2.3.0"
        );
        cache.cacheNotFound(k1);
        cache.cacheNotFound(k2);
        assertTrue(cache.isKnown404(k1));
        assertTrue(cache.isKnown404(k2));

        CompletableFuture<Void> future = cache.invalidateBatch(List.of(k1, k2));
        future.join();

        assertFalse(cache.isKnown404(k1));
        assertFalse(cache.isKnown404(k2));
    }

    @Test
    void invalidateBatchWithEmptyListSucceeds() {
        CompletableFuture<Void> future = cache.invalidateBatch(List.of());
        future.join();
        // Should complete without error
    }

    @Test
    void invalidateBatchWithNullSucceeds() {
        CompletableFuture<Void> future = cache.invalidateBatch(null);
        future.join();
    }

    @Test
    void differentScopesSameArtifactAreSeparateEntries() {
        final NegativeCacheKey group = new NegativeCacheKey(
            "libs-group", "maven", "com.example:foo", "1.0.0"
        );
        final NegativeCacheKey proxy = new NegativeCacheKey(
            "maven-central", "maven", "com.example:foo", "1.0.0"
        );
        cache.cacheNotFound(group);
        assertTrue(cache.isKnown404(group));
        assertFalse(cache.isKnown404(proxy));
    }

    @Test
    void l1TtlExpiryWorks() throws InterruptedException {
        // Create cache with very short TTL
        final NegativeCacheConfig shortTtl = new NegativeCacheConfig(
            Duration.ofMillis(50), 50_000, false,
            5_000, Duration.ofMillis(50),
            5_000_000, Duration.ofDays(7)
        );
        final NegativeCache shortCache = new NegativeCache(shortTtl);
        final NegativeCacheKey key = new NegativeCacheKey(
            "test", "maven", "com.example:expiring", "1.0.0"
        );
        shortCache.cacheNotFound(key);
        assertTrue(shortCache.isKnown404(key));

        // Wait for TTL to expire
        Thread.sleep(100);
        shortCache.cleanup();

        assertFalse(shortCache.isKnown404(key));
    }

    @Test
    void asyncCheckReturnsCorrectResult() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "npm-proxy", "npm", "@types/node", "20.0.0"
        );
        assertFalse(cache.isKnown404Async(key).join());
        cache.cacheNotFound(key);
        assertTrue(cache.isKnown404Async(key).join());
    }

    @Test
    void registryHoldsSharedInstance() {
        final NegativeCacheRegistry registry = NegativeCacheRegistry.instance();
        registry.clear();
        registry.setSharedCache(cache);
        assertSame(cache, registry.sharedCache());
        registry.clear();
    }

    @Test
    void sizeTracksEntries() {
        assertEquals(0, cache.size());
        cache.cacheNotFound(new NegativeCacheKey("s", "t", "a", "v1"));
        assertEquals(1, cache.size());
        cache.cacheNotFound(new NegativeCacheKey("s", "t", "a", "v2"));
        assertEquals(2, cache.size());
    }

    @Test
    void clearRemovesAllEntries() {
        cache.cacheNotFound(new NegativeCacheKey("s", "t", "a", "v1"));
        cache.cacheNotFound(new NegativeCacheKey("s", "t", "b", "v2"));
        assertTrue(cache.size() > 0);
        cache.clear();
        assertEquals(0, cache.size());
    }
}
