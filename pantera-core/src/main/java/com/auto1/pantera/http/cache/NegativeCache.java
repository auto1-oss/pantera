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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Caches 404 (Not Found) responses to avoid repeated upstream requests for missing artifacts.
 * This is critical for proxy repositories to avoid hammering upstream repositories with
 * requests for artifacts that don't exist (e.g., optional dependencies, typos).
 * 
 * Thread-safe, high-performance cache using Caffeine with automatic TTL expiry.
 * 
 * Performance impact: Eliminates 100% of repeated 404 requests, reducing load on both
 * Pantera and upstream repositories.
 * 
 * @since 0.11
 */
public final class NegativeCache {
    
    /**
     * Default TTL for negative cache (24 hours).
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    
    /**
     * Default maximum cache size (50,000 entries).
     * At ~150 bytes per entry = ~7.5MB maximum memory usage.
     */
    private static final int DEFAULT_MAX_SIZE = 50_000;
    
    /**
     * Sentinel value for negative cache (we only care about presence, not value).
     */
    private static final Boolean CACHED = Boolean.TRUE;
    
    /**
     * L1 cache for 404 responses (in-memory, hot data).
     * Thread-safe, high-performance, with automatic TTL expiry.
     */
    private final Cache<Key, Boolean> notFoundCache;
    
    /**
     * L2 cache (Valkey/Redis, warm data) - optional.
     */
    private final RedisAsyncCommands<String, byte[]> l2;
    
    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;
    
    /**
     * Whether negative caching is enabled.
     */
    private final boolean enabled;
    
    /**
     * Cache TTL for L2.
     */
    private final Duration ttl;
    
    /**
     * Repository type for cache key namespacing.
     */
    private final String repoType;

    /**
     * Repository name for cache key isolation.
     * Prevents cache collisions in group repositories.
     */
    private final String repoName;

    /**
     * Create negative cache using unified NegativeCacheConfig.
     * @param repoType Repository type for cache key namespacing (e.g., "npm", "pypi", "go")
     * @param repoName Repository name for cache key isolation
     */
    public NegativeCache(final String repoType, final String repoName) {
        this(repoType, repoName, NegativeCacheConfig.getInstance());
    }

    /**
     * Create negative cache with explicit config.
     * @param repoType Repository type for cache key namespacing (e.g., "npm", "pypi", "go")
     * @param repoName Repository name for cache key isolation
     * @param config Unified negative cache configuration
     */
    public NegativeCache(final String repoType, final String repoName, final NegativeCacheConfig config) {
        this(
            config.l2Ttl(),
            true,
            config.isValkeyEnabled() ? config.l1MaxSize() : config.maxSize(),
            config.isValkeyEnabled() ? config.l1Ttl() : config.ttl(),
            GlobalCacheConfig.valkeyConnection()
                .filter(v -> config.isValkeyEnabled())
                .map(ValkeyConnection::async)
                .orElse(null),
            repoType,
            repoName
        );
    }

    /**
     * Create negative cache with default 24h TTL and 50K max size (enabled).
     * @deprecated Use {@link #NegativeCache(String, String)} instead
     */
    @Deprecated
    public NegativeCache() {
        this(DEFAULT_TTL, true, DEFAULT_MAX_SIZE, DEFAULT_TTL, null, "unknown", "default");
    }

    /**
     * Create negative cache with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final ValkeyConnection valkey) {
        this(
            DEFAULT_TTL,
            true,
            valkey != null ? Math.max(1000, DEFAULT_MAX_SIZE / 10) : DEFAULT_MAX_SIZE,
            valkey != null ? Duration.ofMinutes(5) : DEFAULT_TTL,
            valkey != null ? valkey.async() : null,
            "unknown",
            "default"
        );
    }

    /**
     * Create negative cache with custom TTL and default max size.
     * @param ttl Time-to-live for cached 404s
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl) {
        this(ttl, true, DEFAULT_MAX_SIZE, ttl, null, "unknown", "default");
    }

    /**
     * Create negative cache with custom TTL and enable flag.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl, final boolean enabled) {
        this(ttl, enabled, DEFAULT_MAX_SIZE, ttl, null, "unknown", "default");
    }

    /**
     * Create negative cache with custom TTL, enable flag, and max size.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize,
            final ValkeyConnection valkey) {
        this(
            ttl,
            enabled,
            valkey != null ? Math.max(1000, maxSize / 10) : maxSize,
            valkey != null ? Duration.ofMinutes(5) : ttl,
            valkey != null ? valkey.async() : null,
            "unknown",
            "default"
        );
    }

    /**
     * Create negative cache with custom TTL, enable flag, max size, and repository name.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     * @param repoName Repository name for cache key isolation
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize,
            final ValkeyConnection valkey, final String repoName) {
        this(
            ttl,
            enabled,
            valkey != null ? Math.max(1000, maxSize / 10) : maxSize,
            valkey != null ? Duration.ofMinutes(5) : ttl,
            valkey != null ? valkey.async() : null,
            "unknown",
            repoName
        );
    }

    /**
     * Create negative cache with custom TTL, enable flag, max size, repo type, and repository name.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     * @param repoType Repository type for cache key namespacing (e.g., "npm", "pypi", "go")
     * @param repoName Repository name for cache key isolation
     * @deprecated Use {@link #NegativeCache(String, String, NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize,
            final ValkeyConnection valkey, final String repoType, final String repoName) {
        this(
            ttl,
            enabled,
            valkey != null ? Math.max(1000, maxSize / 10) : maxSize,
            valkey != null ? Duration.ofMinutes(5) : ttl,
            valkey != null ? valkey.async() : null,
            repoType,
            repoName
        );
    }

    /**
     * Primary constructor - all other constructors delegate to this one.
     * @param ttl TTL for L2 cache
     * @param enabled Whether negative caching is enabled
     * @param l1MaxSize Maximum size for L1 cache
     * @param l1Ttl TTL for L1 cache
     * @param l2Commands Redis commands for L2 cache (null for single-tier)
     * @param repoType Repository type for cache key namespacing
     * @param repoName Repository name for cache key isolation
     */
    @SuppressWarnings("PMD.NullAssignment")
    private NegativeCache(final Duration ttl, final boolean enabled, final int l1MaxSize,
            final Duration l1Ttl, final RedisAsyncCommands<String, byte[]> l2Commands,
            final String repoType, final String repoName) {
        this.enabled = enabled;
        this.twoTier = l2Commands != null;
        this.l2 = l2Commands;
        this.ttl = ttl;
        this.repoType = repoType != null ? repoType : "unknown";
        this.repoName = repoName != null ? repoName : "default";
        this.notFoundCache = Caffeine.newBuilder()
            .maximumSize(l1MaxSize)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
    }
    
    /**
     * Check if key is in negative cache (known 404).
     * Thread-safe - Caffeine handles synchronization.
     * Caffeine automatically removes expired entries.
     * 
     * PERFORMANCE: Only checks L1 cache to avoid blocking request thread.
     * L2 queries happen asynchronously in background.
     * 
     * @param key Key to check
     * @return True if cached in L1 as not found
     */
    public boolean isNotFound(final Key key) {
        if (!this.enabled) {
            return false;
        }
        
        final long startNanos = System.nanoTime();
        final boolean found = this.notFoundCache.getIfPresent(key) != null;

        // Track L1 metrics
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (found) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
            } else {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
            }
        }

        return found;
    }
    
    /**
     * Async check if key is in negative cache (known 404).
     * Checks both L1 and L2, suitable for async callers.
     * 
     * @param key Key to check
     * @return Future with true if cached as not found
     */
    public CompletableFuture<Boolean> isNotFoundAsync(final Key key) {
        if (!this.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check L1 first
        final long l1StartNanos = System.nanoTime();
        if (this.notFoundCache.getIfPresent(key) != null) {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
            }
            return CompletableFuture.completedFuture(true);
        }

        // L1 MISS
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
        }
        
        // Check L2 if enabled
        if (this.twoTier) {
            final String redisKey = "negative:" + this.repoType + ":" + this.repoName + ":" + key.string();
            final long l2StartNanos = System.nanoTime();

            return this.l2.get(redisKey)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> {
                    // Track L2 error - metrics handled elsewhere
                    return null;
                })
                .thenApply(l2Bytes -> {
                    final long durationMs = (System.nanoTime() - l2StartNanos) / 1_000_000;

                    if (l2Bytes != null) {
                        // L2 HIT
                        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l2");
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l2", "get", durationMs);
                        }
                        this.notFoundCache.put(key, CACHED);
                        return true;
                    }

                    // L2 MISS
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l2");
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l2", "get", durationMs);
                    }
                    return false;
                });
        }
        
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Cache a key as not found (404).
     * Thread-safe - Caffeine handles synchronization and eviction.
     * 
     * @param key Key to cache as not found
     */
    public void cacheNotFound(final Key key) {
        if (!this.enabled) {
            return;
        }
        
        // Cache in L1
        this.notFoundCache.put(key, CACHED);
        
        // Cache in L2 (if enabled)
        if (this.twoTier) {
            final String redisKey = "negative:" + this.repoType + ":" + this.repoName + ":" + key.string();
            final byte[] value = new byte[]{1};  // Sentinel value
            final long seconds = this.ttl.getSeconds();
            this.l2.setex(redisKey, seconds, value);
        }
    }
    
    /**
     * Invalidate specific entry (e.g., when artifact is deployed).
     * Thread-safe - Caffeine handles synchronization.
     * 
     * @param key Key to invalidate
     */
    public void invalidate(final Key key) {
        // Invalidate L1
        this.notFoundCache.invalidate(key);
        
        // Invalidate L2 (if enabled)
        if (this.twoTier) {
            final String redisKey = "negative:" + this.repoType + ":" + this.repoName + ":" + key.string();
            this.l2.del(redisKey);
        }
    }

    /**
     * Invalidate all entries matching a prefix pattern.
     * Thread-safe - Caffeine handles synchronization.
     *
     * @param prefix Key prefix to match
     */
    public void invalidatePrefix(final String prefix) {
        // Invalidate L1
        this.notFoundCache.asMap().keySet().removeIf(key -> key.string().startsWith(prefix));

        // Invalidate L2 (if enabled)
        if (this.twoTier) {
            final String scanPattern = "negative:" + this.repoType + ":" + this.repoName + ":" + prefix + "*";
            this.scanAndDelete(scanPattern);
        }
    }

    /**
     * Clear entire cache.
     * Thread-safe - Caffeine handles synchronization.
     */
    public void clear() {
        // Clear L1
        this.notFoundCache.invalidateAll();

        // Clear L2 (if enabled) - scan and delete all negative cache keys
        if (this.twoTier) {
            this.scanAndDelete("negative:" + this.repoType + ":" + this.repoName + ":*");
        }
    }
    
    /**
     * Recursive async scan that collects all matching keys and deletes them in batches.
     * Uses SCAN instead of KEYS to avoid blocking the Redis server.
     *
     * @param pattern Glob pattern to match keys
     * @return Future that completes when all matching keys are deleted
     */
    private CompletableFuture<Void> scanAndDelete(final String pattern) {
        return this.scanAndDeleteStep(ScanCursor.INITIAL, pattern);
    }

    /**
     * Single step of the recursive SCAN-and-delete loop.
     *
     * @param cursor Current scan cursor
     * @param pattern Glob pattern to match keys
     * @return Future that completes when this step and all subsequent steps finish
     */
    private CompletableFuture<Void> scanAndDeleteStep(
        final ScanCursor cursor, final String pattern
    ) {
        return this.l2.scan(cursor, ScanArgs.Builder.matches(pattern).limit(100))
            .toCompletableFuture()
            .thenCompose(result -> {
                if (!result.getKeys().isEmpty()) {
                    this.l2.del(result.getKeys().toArray(new String[0]));
                }
                if (result.isFinished()) {
                    return CompletableFuture.completedFuture(null);
                }
                return this.scanAndDeleteStep(result, pattern);
            });
    }

    /**
     * Remove expired entries (periodic cleanup).
     * Caffeine handles expiry automatically, but calling this
     * triggers immediate cleanup instead of lazy removal.
     */
    public void cleanup() {
        this.notFoundCache.cleanUp();
    }
    
    /**
     * Get current cache size.
     * Thread-safe - Caffeine handles synchronization.
     * @return Number of entries in cache
     */
    public long size() {
        return this.notFoundCache.estimatedSize();
    }
    
    /**
     * Get cache statistics from Caffeine.
     * Includes hit rate, miss rate, eviction count, etc.
     * @return Caffeine cache statistics
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return this.notFoundCache.stats();
    }
    
    /**
     * Check if negative caching is enabled.
     * @return True if enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }
}
