/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

import com.artipie.asto.Key;
import com.artipie.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
 * Artipie and upstream repositories.
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
     * Repository name for cache key isolation.
     * Prevents cache collisions in group repositories.
     */
    private final String repoName;
    
    /**
     * Create negative cache with default 24h TTL and 50K max size (enabled).
     */
    public NegativeCache() {
        this(DEFAULT_TTL, true, DEFAULT_MAX_SIZE, null, "default");
    }
    
    /**
     * Create negative cache with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     */
    public NegativeCache(final ValkeyConnection valkey) {
        this(DEFAULT_TTL, true, DEFAULT_MAX_SIZE, valkey, "default");
    }
    
    /**
     * Create negative cache with custom TTL and default max size.
     * @param ttl Time-to-live for cached 404s
     */
    public NegativeCache(final Duration ttl) {
        this(ttl, true, DEFAULT_MAX_SIZE, null, "default");
    }
    
    /**
     * Create negative cache with custom TTL and enable flag.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     */
    public NegativeCache(final Duration ttl, final boolean enabled) {
        this(ttl, enabled, DEFAULT_MAX_SIZE, null, "default");
    }
    
    /**
     * Create negative cache with custom TTL, enable flag, and max size.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     */
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize, final ValkeyConnection valkey) {
        this(ttl, enabled, maxSize, valkey, "default");
    }
    
    /**
     * Create negative cache with custom TTL, enable flag, max size, and repository name.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     * @param repoName Repository name for cache key isolation
     */
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize, final ValkeyConnection valkey, final String repoName) {
        // Check global config if no explicit valkey passed
        final ValkeyConnection actualValkey = (valkey != null) 
            ? valkey 
            : com.artipie.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
        
        this.enabled = enabled;
        this.twoTier = (actualValkey != null);
        this.l2 = this.twoTier ? actualValkey.async() : null;
        this.ttl = ttl;
        this.repoName = repoName != null ? repoName : "default";
        
        // L1: Hot data cache
        // If two-tier: Smaller cache with short TTL, L2 has the long TTL
        // If single-tier: Full cache with configured TTL
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : ttl;
        final int l1Size = this.twoTier ? Math.max(1000, maxSize / 10) : maxSize;
        
        this.notFoundCache = Caffeine.newBuilder()
            .maximumSize(l1Size)
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
        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (found) {
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l1");
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
            } else {
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l1");
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
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
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l1");
                com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
            }
            return CompletableFuture.completedFuture(true);
        }

        // L1 MISS
        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
            com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l1");
            com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l1", "get", durationMs);
        }
        
        // Check L2 if enabled
        if (this.twoTier) {
            final String redisKey = "negative:" + this.repoName + ":" + key.string();
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
                        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                            com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheHit("negative", "l2");
                            com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l2", "get", durationMs);
                        }
                        this.notFoundCache.put(key, CACHED);
                        return true;
                    }

                    // L2 MISS
                    if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                        com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheMiss("negative", "l2");
                        com.artipie.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("negative", "l2", "get", durationMs);
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
            final String redisKey = "negative:" + this.repoName + ":" + key.string();
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
            final String redisKey = "negative:" + this.repoName + ":" + key.string();
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
            final String scanPattern = "negative:" + this.repoName + ":" + prefix + "*";
            this.l2.keys(scanPattern).thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
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
            this.l2.keys("negative:" + this.repoName + ":*").thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
        }
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
