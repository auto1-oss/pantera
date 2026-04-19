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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unified negative cache for 404 responses — single shared instance per JVM.
 *
 * <p>Keyed by {@link NegativeCacheKey} ({@code scope:repoType:artifactName:artifactVersion}).
 * Hosted, proxy, and group scopes all share one L1 Caffeine + optional L2 Valkey bean.
 *
 * <p>New callers should use the {@link NegativeCacheKey}-based API:
 * <ul>
 *   <li>{@link #isKnown404(NegativeCacheKey)}</li>
 *   <li>{@link #cacheNotFound(NegativeCacheKey)}</li>
 *   <li>{@link #invalidate(NegativeCacheKey)}</li>
 *   <li>{@link #invalidateBatch(List)}</li>
 * </ul>
 *
 * <p>Legacy {@link Key}-based methods are retained for backward compatibility but
 * delegate through a synthetic {@link NegativeCacheKey} built from the instance's
 * {@code repoType} and {@code repoName}.
 *
 * <p>Thread-safe, high-performance cache using Caffeine with automatic TTL expiry.
 *
 * @since 0.11
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class NegativeCache {

    /**
     * Default TTL for negative cache (24 hours).
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Default maximum cache size (50,000 entries).
     */
    private static final int DEFAULT_MAX_SIZE = 50_000;

    /**
     * Sentinel value for negative cache (we only care about presence, not value).
     */
    private static final Boolean CACHED = Boolean.TRUE;

    /**
     * L1 cache for 404 responses (in-memory, hot data).
     * Keyed by {@link NegativeCacheKey#flat()}.
     */
    private final Cache<String, Boolean> notFoundCache;

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
     * Repository type for legacy key construction.
     */
    private final String repoType;

    /**
     * Repository name for legacy key construction.
     */
    private final String repoName;

    // -----------------------------------------------------------------------
    //  Primary constructor (all others delegate here)
    // -----------------------------------------------------------------------

    /**
     * Primary constructor.
     *
     * @param ttl TTL for L2 cache
     * @param enabled Whether negative caching is enabled
     * @param l1MaxSize Maximum size for L1 cache
     * @param l1Ttl TTL for L1 cache
     * @param l2Commands Redis commands for L2 cache (null for single-tier)
     * @param repoType Repository type for legacy key namespacing
     * @param repoName Repository name for legacy key isolation
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

    // -----------------------------------------------------------------------
    //  Public constructors — NEW (preferred)
    // -----------------------------------------------------------------------

    /**
     * Create negative cache from config (the single-instance wiring constructor).
     *
     * @param config Unified negative cache configuration
     */
    public NegativeCache(final NegativeCacheConfig config) {
        this(
            config.l2Ttl(),
            true,
            config.isValkeyEnabled() ? config.l1MaxSize() : config.maxSize(),
            config.isValkeyEnabled() ? config.l1Ttl() : config.ttl(),
            GlobalCacheConfig.valkeyConnection()
                .filter(v -> config.isValkeyEnabled())
                .map(ValkeyConnection::async)
                .orElse(null),
            "unified",
            "shared"
        );
    }

    // -----------------------------------------------------------------------
    //  Public constructors — LEGACY (backward compat, delegate to primary)
    // -----------------------------------------------------------------------

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
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache() {
        this(DEFAULT_TTL, true, DEFAULT_MAX_SIZE, DEFAULT_TTL, null, "unknown", "default");
    }

    /**
     * Create negative cache with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
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
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
     */
    @Deprecated
    public NegativeCache(final Duration ttl) {
        this(ttl, true, DEFAULT_MAX_SIZE, ttl, null, "unknown", "default");
    }

    /**
     * Create negative cache with custom TTL and enable flag.
     * @param ttl Time-to-live for cached 404s
     * @param enabled Whether negative caching is enabled
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
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
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
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
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
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
     * @deprecated Use {@link #NegativeCache(NegativeCacheConfig)} instead
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

    // -----------------------------------------------------------------------
    //  NEW composite-key API
    // -----------------------------------------------------------------------

    /**
     * Check if a composite key is in negative cache (known 404).
     * Checks L1 only (synchronous). Use {@link #isKnown404Async(NegativeCacheKey)}
     * for L1+L2.
     *
     * @param key Composite key to check
     * @return true if cached in L1 as not found
     */
    public boolean isKnown404(final NegativeCacheKey key) {
        if (!this.enabled) {
            return false;
        }
        final String flat = key.flat();
        final long startNanos = System.nanoTime();
        final boolean found = this.notFoundCache.getIfPresent(flat) != null;
        recordL1Metrics(found, startNanos);
        return found;
    }

    /**
     * Async check — inspects L1 then L2.
     *
     * @param key Composite key to check
     * @return future resolving to true if the key is a known 404
     */
    public CompletableFuture<Boolean> isKnown404Async(final NegativeCacheKey key) {
        if (!this.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        final String flat = key.flat();
        final long l1Start = System.nanoTime();
        if (this.notFoundCache.getIfPresent(flat) != null) {
            recordL1Metrics(true, l1Start);
            return CompletableFuture.completedFuture(true);
        }
        recordL1Metrics(false, l1Start);
        if (this.twoTier) {
            return l2Get(flat);
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Cache a composite key as not found (404) in L1 + L2.
     *
     * @param key Composite key to cache
     */
    public void cacheNotFound(final NegativeCacheKey key) {
        if (!this.enabled) {
            return;
        }
        final String flat = key.flat();
        this.notFoundCache.put(flat, CACHED);
        if (this.twoTier) {
            l2Set("negative:" + flat);
        }
    }

    /**
     * Invalidate a single composite key from L1 + L2.
     *
     * @param key Composite key to invalidate
     */
    public void invalidate(final NegativeCacheKey key) {
        final String flat = key.flat();
        this.notFoundCache.invalidate(flat);
        if (this.twoTier) {
            this.l2.del("negative:" + flat);
        }
    }

    /**
     * Synchronously invalidate a batch of composite keys from L1 + L2.
     * Returns a future that completes when both tiers are updated.
     *
     * @param keys List of composite keys to invalidate
     * @return future completing when invalidation is done
     */
    public CompletableFuture<Void> invalidateBatch(final List<NegativeCacheKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        // Invalidate L1 synchronously
        for (final NegativeCacheKey key : keys) {
            this.notFoundCache.invalidate(key.flat());
        }
        // Invalidate L2 asynchronously
        if (this.twoTier) {
            final String[] redisKeys = keys.stream()
                .map(k -> "negative:" + k.flat())
                .toArray(String[]::new);
            return this.l2.del(redisKeys)
                .toCompletableFuture()
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .exceptionally(err -> 0L)
                .thenApply(ignored -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    // -----------------------------------------------------------------------
    //  LEGACY Key-based API (backward compat — delegates to composite-key API)
    // -----------------------------------------------------------------------

    /**
     * Check if key is in negative cache (known 404).
     *
     * @param key Key to check
     * @return True if cached in L1 as not found
     */
    public boolean isNotFound(final Key key) {
        if (!this.enabled) {
            return false;
        }
        final String flat = legacyFlat(key);
        final long startNanos = System.nanoTime();
        final boolean found = this.notFoundCache.getIfPresent(flat) != null;
        recordL1Metrics(found, startNanos);
        return found;
    }

    /**
     * Async check if key is in negative cache (known 404).
     * Checks both L1 and L2.
     *
     * @param key Key to check
     * @return Future with true if cached as not found
     */
    public CompletableFuture<Boolean> isNotFoundAsync(final Key key) {
        if (!this.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        final String flat = legacyFlat(key);
        final long l1Start = System.nanoTime();
        if (this.notFoundCache.getIfPresent(flat) != null) {
            recordL1Metrics(true, l1Start);
            return CompletableFuture.completedFuture(true);
        }
        recordL1Metrics(false, l1Start);
        if (this.twoTier) {
            return l2Get(flat);
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Cache a key as not found (404).
     *
     * @param key Key to cache as not found
     */
    public void cacheNotFound(final Key key) {
        if (!this.enabled) {
            return;
        }
        final String flat = legacyFlat(key);
        this.notFoundCache.put(flat, CACHED);
        if (this.twoTier) {
            l2Set("negative:" + flat);
        }
    }

    /**
     * Invalidate specific entry (e.g., when artifact is deployed).
     *
     * @param key Key to invalidate
     */
    public void invalidate(final Key key) {
        final String flat = legacyFlat(key);
        this.notFoundCache.invalidate(flat);
        if (this.twoTier) {
            this.l2.del("negative:" + flat);
        }
    }

    /**
     * Invalidate all entries matching a prefix pattern.
     *
     * @param prefix Key prefix to match
     */
    public void invalidatePrefix(final String prefix) {
        final String pfx = this.repoType + ":" + this.repoName + ":" + prefix;
        this.notFoundCache.asMap().keySet().removeIf(k -> k.startsWith(pfx));
        if (this.twoTier) {
            scanAndDelete("negative:" + pfx + "*");
        }
    }

    // -----------------------------------------------------------------------
    //  Utility / lifecycle
    // -----------------------------------------------------------------------

    /**
     * Clear entire cache.
     */
    public void clear() {
        this.notFoundCache.invalidateAll();
        if (this.twoTier) {
            scanAndDelete("negative:*");
        }
    }

    /**
     * Remove expired entries (periodic cleanup).
     */
    public void cleanup() {
        this.notFoundCache.cleanUp();
    }

    /**
     * Get current cache size.
     *
     * @return Number of entries in cache
     */
    public long size() {
        return this.notFoundCache.estimatedSize();
    }

    /**
     * Get cache statistics from Caffeine.
     *
     * @return Caffeine cache statistics
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return this.notFoundCache.stats();
    }

    /**
     * Check if negative caching is enabled.
     *
     * @return True if enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Build a flat string for legacy Key-based calls.
     */
    private String legacyFlat(final Key key) {
        return this.repoType + ":" + this.repoName + ":" + key.string();
    }

    /**
     * L2 GET — returns true if found, promotes to L1.
     */
    private CompletableFuture<Boolean> l2Get(final String flat) {
        final String redisKey = "negative:" + flat;
        final long l2Start = System.nanoTime();
        return this.l2.get(redisKey)
            .toCompletableFuture()
            .orTimeout(100, TimeUnit.MILLISECONDS)
            .exceptionally(err -> null)
            .thenApply(l2Bytes -> {
                final long durationMs = (System.nanoTime() - l2Start) / 1_000_000;
                if (l2Bytes != null) {
                    recordL2Metrics(true, durationMs);
                    this.notFoundCache.put(flat, CACHED);
                    return true;
                }
                recordL2Metrics(false, durationMs);
                return false;
            });
    }

    /**
     * L2 SET with TTL.
     */
    private void l2Set(final String redisKey) {
        this.l2.setex(redisKey, this.ttl.getSeconds(), new byte[]{1});
    }

    private void recordL1Metrics(final boolean hit, final long startNanos) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            final com.auto1.pantera.metrics.MicrometerMetrics m =
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance();
            if (hit) {
                m.recordCacheHit("negative", "l1");
            } else {
                m.recordCacheMiss("negative", "l1");
            }
            m.recordCacheOperationDuration("negative", "l1", "get", durationMs);
        }
    }

    private static void recordL2Metrics(final boolean hit, final long durationMs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final com.auto1.pantera.metrics.MicrometerMetrics m =
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance();
            if (hit) {
                m.recordCacheHit("negative", "l2");
            } else {
                m.recordCacheMiss("negative", "l2");
            }
            m.recordCacheOperationDuration("negative", "l2", "get", durationMs);
        }
    }

    /**
     * Recursive async scan that collects all matching keys and deletes them.
     */
    private CompletableFuture<Void> scanAndDelete(final String pattern) {
        return scanAndDeleteStep(ScanCursor.INITIAL, pattern);
    }

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
                return scanAndDeleteStep(result, pattern);
            });
    }
}
