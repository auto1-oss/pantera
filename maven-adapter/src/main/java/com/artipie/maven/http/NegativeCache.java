/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

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
 * This is critical for proxy repositories to avoid hammering upstream (Maven Central) with
 * requests for artifacts that don't exist (e.g., optional dependencies).
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
        this(DEFAULT_TTL, true, DEFAULT_MAX_SIZE, valkey);
    }
    
    /**
     * Create negative cache with custom TTL and default max size.
     * @param ttl Time-to-live for cached 404s
     */
    public NegativeCache(final Duration ttl) {
        this(ttl, true, DEFAULT_MAX_SIZE, null);
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
     * @param valkey Valkey connection for L2 cache (null for single-tier)
     */
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize, final ValkeyConnection valkey) {
        this(ttl, enabled, maxSize, valkey, "default");
    }
    
    /**
     * Create negative cache with custom TTL, enable flag, max size, and repository name.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null for single-tier)
     * @param repoName Repository name for cache key isolation
     */
    @SuppressWarnings({"PMD.NullAssignment", "PMD.UselessParentheses", 
        "PMD.ConstructorOnlyInitializesOrCallOtherConstructors", "PMD.AvoidCatchingGenericException"})
    public NegativeCache(final Duration ttl, final boolean enabled, final int maxSize, final ValkeyConnection valkey, final String repoName) {
        this.enabled = enabled;
        this.twoTier = valkey != null;
        this.l2 = this.twoTier ? valkey.async() : null;
        this.ttl = ttl;
        this.repoName = repoName != null ? repoName : "default";
        
        // L1: Hot data cache (config calculation required before cache init)
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : ttl;
        final int l1Size = this.twoTier ? Math.max(1000, maxSize / 10) : maxSize;
        
        this.notFoundCache = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        
        // Initialize OpenTelemetry metrics (after cache is created)
        if (this.enabled && com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            // Metrics are optional - silent failure is acceptable
            this.registerMetrics();
        }
    }
    
    /**
     * Register cache size metrics (isolated to avoid PMD violations).
     * Metrics registration failures are silently ignored as they are optional.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void registerMetrics() {
        try {
            com.artipie.metrics.otel.OtelMetrics.get()
                .registerCacheSize("maven_negative", "l1", 
                    () -> this.notFoundCache.estimatedSize());
        } catch (RuntimeException ex) {
            // Metrics registration failed - silently ignore
            // Empty catch is acceptable: metrics are optional, no action needed
        }
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
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            final double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            if (found) {
                com.artipie.metrics.otel.OtelMetrics.get().recordL1Hit("maven_negative", durationMs);
            } else {
                com.artipie.metrics.otel.OtelMetrics.get().recordL1Miss("maven_negative", durationMs);
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
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity"})
    public CompletableFuture<Boolean> isNotFoundAsync(final Key key) {
        if (!this.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check L1 first
        final long l1StartNanos = System.nanoTime();
        if (this.notFoundCache.getIfPresent(key) != null) {
            if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
                final double durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000.0;
                com.artipie.metrics.otel.OtelMetrics.get().recordL1Hit("maven_negative", durationMs);
            }
            return CompletableFuture.completedFuture(true);
        }
        
        // L1 MISS
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            final double durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000.0;
            com.artipie.metrics.otel.OtelMetrics.get().recordL1Miss("maven_negative", durationMs);
        }
        
        // Check L2 if enabled
        if (this.twoTier) {
            final String redisKey = "maven:negative:" + this.repoName + ":" + key.string();
            final long l2StartNanos = System.nanoTime();
            
            return this.l2.get(redisKey)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> {
                    // Track L2 error
                    if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
                        final double durationMs = (System.nanoTime() - l2StartNanos) / 1_000_000.0;
                        final String errorType = err instanceof java.util.concurrent.TimeoutException
                            ? "timeout" : "connection_error";
                        com.artipie.metrics.otel.OtelMetrics.get()
                            .recordL2Error("maven_negative", errorType, durationMs);
                    }
                    return null;
                })
                .thenApply(l2Bytes -> {
                    final double durationMs = (System.nanoTime() - l2StartNanos) / 1_000_000.0;
                    
                    if (l2Bytes != null) {
                        // L2 HIT
                        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
                            com.artipie.metrics.otel.OtelMetrics.get()
                                .recordL2Hit("maven_negative", durationMs);
                        }
                        this.notFoundCache.put(key, CACHED);
                        return true;
                    }
                    
                    // L2 MISS
                    if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
                        com.artipie.metrics.otel.OtelMetrics.get()
                            .recordL2Miss("maven_negative", durationMs);
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
            final String redisKey = "maven:negative:" + this.repoName + ":" + key.string();
            final byte[] value = {1};  // Sentinel value
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
            final String redisKey = "maven:negative:" + this.repoName + ":" + key.string();
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
            final String scanPattern = "maven:negative:" + this.repoName + ":" + prefix + "*";
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
        
        // Clear L2 (if enabled)
        if (this.twoTier) {
            this.l2.keys("maven:negative:" + this.repoName + ":*").thenAccept(keys -> {
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
