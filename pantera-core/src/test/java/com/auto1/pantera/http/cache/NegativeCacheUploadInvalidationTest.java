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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulates the upload-invalidation flow:
 * <ol>
 *   <li>Artifact is cached as 404 (negative cache hit).</li>
 *   <li>Artifact is published (upload).</li>
 *   <li>Negative cache entry for that key is invalidated.</li>
 *   <li>Next GET no longer sees the stale 404.</li>
 * </ol>
 *
 * @since 2.2.0
 */
final class NegativeCacheUploadInvalidationTest {

    private NegativeCache cache;

    @BeforeEach
    void setUp() {
        final NegativeCacheConfig config = new NegativeCacheConfig(
            Duration.ofHours(1), 50_000, false,
            5_000, Duration.ofMinutes(5),
            5_000_000, Duration.ofDays(7)
        );
        this.cache = new NegativeCache(config);
    }

    @Test
    void publishInvalidatesNegCacheEntry() {
        // Setup: artifact is cached as 404
        final NegativeCacheKey key = new NegativeCacheKey(
            "maven-hosted", "maven", "com.example:foo", "1.0.0"
        );
        cache.cacheNotFound(key);
        assertTrue(cache.isKnown404(key), "Entry should be cached as 404");

        // Simulate upload: invalidate the entry synchronously
        cache.invalidateBatch(List.of(key)).join();

        // Verify: next GET does not see stale 404
        assertFalse(cache.isKnown404(key), "Entry should be cleared after publish");
    }

    @Test
    void publishInvalidatesGroupScopeEntry() {
        // Setup: artifact is cached as 404 in both hosted and group scopes
        final NegativeCacheKey hostedKey = new NegativeCacheKey(
            "maven-hosted", "maven", "com.example:bar", "2.0.0"
        );
        final NegativeCacheKey groupKey = new NegativeCacheKey(
            "maven-group", "maven", "com.example:bar", "2.0.0"
        );
        cache.cacheNotFound(hostedKey);
        cache.cacheNotFound(groupKey);
        assertTrue(cache.isKnown404(hostedKey));
        assertTrue(cache.isKnown404(groupKey));

        // Simulate upload: invalidate both scope entries
        cache.invalidateBatch(List.of(hostedKey, groupKey)).join();

        assertFalse(cache.isKnown404(hostedKey));
        assertFalse(cache.isKnown404(groupKey));
    }

    @Test
    void publishDoesNotAffectOtherArtifacts() {
        final NegativeCacheKey target = new NegativeCacheKey(
            "npm-proxy", "npm", "@types/node", "20.0.0"
        );
        final NegativeCacheKey other = new NegativeCacheKey(
            "npm-proxy", "npm", "@types/react", "18.0.0"
        );
        cache.cacheNotFound(target);
        cache.cacheNotFound(other);

        // Only invalidate the target
        cache.invalidateBatch(List.of(target)).join();

        assertFalse(cache.isKnown404(target));
        assertTrue(cache.isKnown404(other), "Other entries should remain");
    }

    @Test
    void multiplePublishesAreIdempotent() {
        final NegativeCacheKey key = new NegativeCacheKey(
            "pypi-proxy", "pypi", "flask", "2.3.0"
        );
        cache.cacheNotFound(key);

        // Double invalidation should succeed without error
        cache.invalidateBatch(List.of(key)).join();
        cache.invalidateBatch(List.of(key)).join();

        assertFalse(cache.isKnown404(key));
    }
}
