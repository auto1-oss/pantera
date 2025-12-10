/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Key;
import com.artipie.cache.GlobalCacheConfig;
import com.artipie.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Negative cache for group repositories.
 * Caches 404 responses per member to avoid repeated queries for missing artifacts.
 * 
 * <p>Key format: {@code negative:group:{group_name}:{member_name}:{path}}</p>
 * 
 * <p>Two-tier architecture:</p>
 * <ul>
 *   <li>L1 (Caffeine): Fast in-memory cache, short TTL</li>
 *   <li>L2 (Valkey/Redis): Distributed cache, full TTL</li>
 * </ul>
 *
 * @since 1.0
 */
public final class GroupNegativeCache {

    /**
     * Default TTL for negative cache (24 hours).
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Default maximum cache size (100,000 entries).
     */
    private static final int DEFAULT_MAX_SIZE = 100_000;

    /**
     * Sentinel value for cache entries.
     */
    private static final Boolean CACHED = Boolean.TRUE;

    /**
     * L1 cache (in-memory).
     */
    private final Cache<String, Boolean> l1Cache;

    /**
     * L2 cache (Valkey/Redis), may be null.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;

    /**
     * Cache TTL for L2.
     */
    private final Duration ttl;

    /**
     * Group repository name.
     */
    private final String groupName;

    /**
     * Whether negative caching is enabled.
     */
    private final boolean enabled;

    /**
     * Create group negative cache with defaults.
     * @param groupName Group repository name
     */
    public GroupNegativeCache(final String groupName) {
        this(groupName, DEFAULT_TTL, true, DEFAULT_MAX_SIZE, null);
    }

    /**
     * Create group negative cache with custom parameters.
     * @param groupName Group repository name
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum L1 cache size
     * @param valkey Optional Valkey connection for L2
     */
    public GroupNegativeCache(
        final String groupName,
        final Duration ttl,
        final boolean enabled,
        final int maxSize,
        final ValkeyConnection valkey
    ) {
        this.groupName = groupName;
        this.ttl = ttl;
        this.enabled = enabled;

        // Check global config if no explicit valkey passed
        final ValkeyConnection actualValkey = (valkey != null)
            ? valkey
            : GlobalCacheConfig.valkeyConnection().orElse(null);

        this.twoTier = (actualValkey != null);
        this.l2 = this.twoTier ? actualValkey.async() : null;

        // L1 cache configuration
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : ttl;
        final int l1Size = this.twoTier ? Math.max(1000, maxSize / 10) : maxSize;

        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
    }

    /**
     * Build cache key.
     * Format: negative:group:{group_name}:{member_name}:{path}
     */
    private String buildKey(final String memberName, final Key path) {
        return "negative:group:" + this.groupName + ":" + memberName + ":" + path.string();
    }

    /**
     * Check if member returned 404 for this path (L1 only for fast path).
     * @param memberName Member repository name
     * @param path Request path
     * @return True if cached as not found in L1
     */
    public boolean isNotFoundL1(final String memberName, final Key path) {
        if (!this.enabled) {
            return false;
        }
        final String key = buildKey(memberName, path);
        final boolean found = this.l1Cache.getIfPresent(key) != null;

        // Record metrics
        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            if (found) {
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("group_negative", "l1");
            } else {
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("group_negative", "l1");
            }
        }
        return found;
    }

    /**
     * Check if member returned 404 - checks L1 first, then L2 if L1 miss.
     * @param memberName Member repository name
     * @param path Request path
     * @return Future with true if cached as not found in either L1 or L2
     */
    public CompletableFuture<Boolean> isNotFoundAsync(final String memberName, final Key path) {
        if (!this.enabled) {
            return CompletableFuture.completedFuture(false);
        }

        final String key = buildKey(memberName, path);

        // Check L1 first (synchronous)
        final boolean foundL1 = this.l1Cache.getIfPresent(key) != null;
        if (foundL1) {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("group_negative", "l1");
            }
            return CompletableFuture.completedFuture(true);
        }

        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("group_negative", "l1");
        }

        // L1 MISS - check L2 if available
        if (!this.twoTier) {
            return CompletableFuture.completedFuture(false);
        }

        return this.l2.get(key)
            .toCompletableFuture()
            .orTimeout(100, TimeUnit.MILLISECONDS)
            .exceptionally(err -> null)
            .thenApply(bytes -> {
                if (bytes != null) {
                    // L2 HIT - promote to L1
                    this.l1Cache.put(key, CACHED);
                    if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                        com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("group_negative", "l2");
                    }
                    return true;
                }
                if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                    com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("group_negative", "l2");
                }
                return false;
            });
    }

    /**
     * Check L2 cache asynchronously (call after L1 miss if needed).
     * @param memberName Member repository name
     * @param path Request path
     * @return Future with true if cached as not found in L2
     */
    public CompletableFuture<Boolean> isNotFoundL2Async(final String memberName, final Key path) {
        if (!this.enabled || !this.twoTier) {
            return CompletableFuture.completedFuture(false);
        }

        final String key = buildKey(memberName, path);
        return this.l2.get(key)
            .toCompletableFuture()
            .orTimeout(100, TimeUnit.MILLISECONDS)
            .exceptionally(err -> null)
            .thenApply(bytes -> {
                if (bytes != null) {
                    // L2 HIT - promote to L1
                    this.l1Cache.put(key, CACHED);
                    if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                        com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("group_negative", "l2");
                    }
                    return true;
                }
                if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                    com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("group_negative", "l2");
                }
                return false;
            });
    }

    /**
     * Cache member as returning 404 for this path.
     * @param memberName Member repository name
     * @param path Request path
     */
    public void cacheNotFound(final String memberName, final Key path) {
        if (!this.enabled) {
            return;
        }

        final String key = buildKey(memberName, path);

        // Cache in L1
        this.l1Cache.put(key, CACHED);

        // Cache in L2 (if enabled)
        if (this.twoTier) {
            final byte[] value = new byte[]{1};  // Sentinel value
            this.l2.setex(key, this.ttl.getSeconds(), value);
        }
    }

    /**
     * Invalidate cached 404 for a member/path (e.g., when artifact is deployed).
     * @param memberName Member repository name
     * @param path Request path
     */
    public void invalidate(final String memberName, final Key path) {
        final String key = buildKey(memberName, path);
        this.l1Cache.invalidate(key);
        if (this.twoTier) {
            this.l2.del(key);
        }
    }

    /**
     * Invalidate all cached 404s for a member.
     * @param memberName Member repository name
     */
    public void invalidateMember(final String memberName) {
        final String prefix = "negative:group:" + this.groupName + ":" + memberName + ":";

        // L1: Remove matching entries
        this.l1Cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));

        // L2: Scan and delete
        if (this.twoTier) {
            this.l2.keys(prefix + "*").thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
        }
    }

    /**
     * Clear entire cache for this group.
     */
    public void clear() {
        final String prefix = "negative:group:" + this.groupName + ":";

        this.l1Cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));

        if (this.twoTier) {
            this.l2.keys(prefix + "*").thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
        }
    }

    /**
     * Get L1 cache size.
     * @return Estimated number of entries
     */
    public long size() {
        return this.l1Cache.estimatedSize();
    }

    /**
     * Check if two-tier caching is enabled.
     * @return True if L2 (Valkey) is configured
     */
    public boolean isTwoTier() {
        return this.twoTier;
    }
}

