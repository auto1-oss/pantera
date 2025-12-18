/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Key;
import com.artipie.cache.GlobalCacheConfig;
import com.artipie.cache.NegativeCacheConfig;
import com.artipie.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Configuration is read from unified {@link NegativeCacheConfig}.</p>
 *
 * @since 1.0
 */
public final class GroupNegativeCache {

    /**
     * Global registry of all GroupNegativeCache instances by group name.
     * Allows L1 cache invalidation without process restart.
     */
    private static final ConcurrentHashMap<String, GroupNegativeCache> INSTANCES = 
        new ConcurrentHashMap<>();

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
    private final Duration l2Ttl;

    /**
     * Group repository name.
     */
    private final String groupName;

    /**
     * Whether negative caching is enabled.
     */
    private final boolean enabled;

    /**
     * Create group negative cache using unified NegativeCacheConfig.
     * @param groupName Group repository name
     */
    public GroupNegativeCache(final String groupName) {
        this(groupName, NegativeCacheConfig.getInstance());
    }

    /**
     * Create group negative cache with explicit config.
     * @param groupName Group repository name
     * @param config Negative cache configuration
     */
    public GroupNegativeCache(final String groupName, final NegativeCacheConfig config) {
        this.groupName = groupName;
        this.enabled = true;

        // Check global valkey connection
        final ValkeyConnection actualValkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        this.twoTier = config.isValkeyEnabled() && (actualValkey != null);
        this.l2 = this.twoTier ? actualValkey.async() : null;
        this.l2Ttl = config.l2Ttl();

        // L1 cache configuration from unified config
        final Duration l1Ttl = this.twoTier ? config.l1Ttl() : config.ttl();
        final int l1Size = this.twoTier ? config.l1MaxSize() : config.maxSize();

        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        
        // Register this instance for global invalidation
        INSTANCES.put(groupName, this);
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
            this.l2.setex(key, this.l2Ttl.getSeconds(), value);
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

    // ========== Static methods for global cache invalidation ==========

    /**
     * Invalidate negative cache entries for a package across ALL group instances.
     * This invalidates both L1 (in-memory) and L2 (Valkey) caches.
     * 
     * <p>Use this when a package is published to ensure group repos can find it.</p>
     * 
     * @param packagePath Package path (e.g., "@retail/backoffice-interaction-notes")
     * @return CompletableFuture that completes when L2 invalidation is done
     */
    public static CompletableFuture<Void> invalidatePackageGlobally(final String packagePath) {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (final GroupNegativeCache instance : INSTANCES.values()) {
            futures.add(instance.invalidatePackageInAllMembers(packagePath));
        }
        futures.add(invalidateGlobalL2Only(packagePath));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Invalidate negative cache entries for a package in a specific group.
     * This invalidates both L1 (in-memory) and L2 (Valkey) caches.
     * 
     * @param groupName Group repository name
     * @param packagePath Package path (e.g., "@retail/backoffice-interaction-notes")
     * @return CompletableFuture that completes when invalidation is done
     */
    public static CompletableFuture<Void> invalidatePackageInGroup(
        final String groupName, 
        final String packagePath
    ) {
        final GroupNegativeCache instance = INSTANCES.get(groupName);
        if (instance != null) {
            return instance.invalidatePackageInAllMembers(packagePath);
        }
        // Group not found - try L2 directly if available
        return invalidateL2Only(groupName, packagePath);
    }

    /**
     * Clear all negative cache entries for a specific group.
     * This clears both L1 (in-memory) and L2 (Valkey) caches.
     * 
     * @param groupName Group repository name
     * @return CompletableFuture that completes when clearing is done
     */
    public static CompletableFuture<Void> clearGroup(final String groupName) {
        final GroupNegativeCache instance = INSTANCES.get(groupName);
        if (instance != null) {
            instance.clear();
            return CompletableFuture.completedFuture(null);
        }
        // Group not found - try L2 directly if available
        return clearL2Only(groupName);
    }

    /**
     * Get list of all registered group names.
     * @return List of group names with active negative caches
     */
    public static List<String> registeredGroups() {
        return new ArrayList<>(INSTANCES.keySet());
    }

    /**
     * Get a specific group's cache instance (for diagnostics).
     * @param groupName Group repository name
     * @return Optional cache instance
     */
    public static java.util.Optional<GroupNegativeCache> getInstance(final String groupName) {
        return java.util.Optional.ofNullable(INSTANCES.get(groupName));
    }

    /**
     * Invalidate package entries in all members of this group.
     * @param packagePath Package path
     * @return CompletableFuture that completes when done
     */
    private CompletableFuture<Void> invalidatePackageInAllMembers(final String packagePath) {
        final String prefix = "negative:group:" + this.groupName + ":";
        final String suffix = ":" + packagePath;
        
        // Invalidate L1: remove all entries for this package across all members
        this.l1Cache.asMap().keySet().removeIf(k -> 
            k.startsWith(prefix) && k.endsWith(suffix)
        );
        
        // Invalidate L2: scan and delete matching keys
        if (this.twoTier) {
            final String pattern = prefix + "*" + suffix;
            return this.l2.keys(pattern)
                .thenCompose(keys -> {
                    if (keys != null && !keys.isEmpty()) {
                        return this.l2.del(keys.toArray(new String[0]))
                            .thenApply(count -> null);
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .toCompletableFuture()
                .thenApply(v -> null);
        }
        
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate L2 cache only (when no L1 instance exists).
     * @param groupName Group name
     * @param packagePath Package path
     * @return CompletableFuture that completes when done
     */
    private static CompletableFuture<Void> invalidateL2Only(
        final String groupName, 
        final String packagePath
    ) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        final String pattern = "negative:group:" + groupName + ":*:" + packagePath;
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async().del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }

    /**
     * Clear L2 cache only for a group (when no L1 instance exists).
     * @param groupName Group name
     * @return CompletableFuture that completes when done
     */
    private static CompletableFuture<Void> clearL2Only(final String groupName) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        final String pattern = "negative:group:" + groupName + ":*";
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async().del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }

    /**
     * Invalidate L2 cache globally for a package, even if no in-memory instances exist.
     * @param packagePath Package path
     * @return CompletableFuture that completes when done
     */
    private static CompletableFuture<Void> invalidateGlobalL2Only(final String packagePath) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String pattern = "negative:group:*:*:" + packagePath;
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async().del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }
}
