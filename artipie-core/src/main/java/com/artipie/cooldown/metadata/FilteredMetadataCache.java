/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

import com.artipie.cache.ValkeyConnection;
import com.artipie.cooldown.metrics.CooldownMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Cache for filtered metadata bytes with dynamic TTL based on cooldown expiration.
 * 
 * <p>Two-tier architecture:</p>
 * <ul>
 *   <li>L1 (in-memory): Fast access, limited size, dynamic TTL per entry</li>
 *   <li>L2 (Valkey/Redis): Shared across instances, larger capacity</li>
 * </ul>
 *
 * <p>TTL Strategy:</p>
 * <ul>
 *   <li>If any version is blocked: TTL = min(blockedUntil) - now (cache until earliest block expires)</li>
 *   <li>If no versions blocked: TTL = max allowed (release dates don't change)</li>
 *   <li>On manual unblock: Cache is invalidated immediately</li>
 * </ul>
 *
 * <p>Cache key format: {@code metadata:{repoType}:{repoName}:{packageName}}</p>
 *
 * @since 1.0
 */
public final class FilteredMetadataCache {

    /**
     * Default L1 cache size (number of packages).
     */
    private static final int DEFAULT_L1_SIZE = 5_000;

    /**
     * Default max TTL when no versions are blocked (24 hours).
     * Since release dates don't change, we can cache for a long time.
     */
    private static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    /**
     * Minimum TTL to avoid excessive cache churn (1 minute).
     */
    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    /**
     * L1 cache (in-memory) with per-entry dynamic TTL.
     */
    private final Cache<String, CacheEntry> l1Cache;

    /**
     * L2 cache connection (Valkey/Redis), may be null.
     */
    private final ValkeyConnection l2Connection;

    /**
     * Maximum TTL for entries with no blocked versions.
     */
    private final Duration maxTtl;

    /**
     * In-flight requests to prevent stampede.
     */
    private final ConcurrentMap<String, CompletableFuture<CacheEntry>> inflight;

    /**
     * Statistics.
     */
    private volatile long l1Hits;
    private volatile long l2Hits;
    private volatile long misses;

    /**
     * Constructor with defaults.
     */
    public FilteredMetadataCache() {
        this(DEFAULT_L1_SIZE, DEFAULT_MAX_TTL, null);
    }

    /**
     * Constructor with Valkey connection.
     *
     * @param valkey Valkey connection for L2 cache
     */
    public FilteredMetadataCache(final ValkeyConnection valkey) {
        this(DEFAULT_L1_SIZE, DEFAULT_MAX_TTL, valkey);
    }

    /**
     * Full constructor.
     *
     * @param l1Size Maximum L1 cache size
     * @param maxTtl Maximum TTL for entries with no blocked versions
     * @param valkey Valkey connection (null for single-tier)
     */
    public FilteredMetadataCache(
        final int l1Size,
        final Duration maxTtl,
        final ValkeyConnection valkey
    ) {
        // L1 cache with dynamic per-entry expiration based on blockedUntil
        // Use scheduler for more timely eviction of expired entries
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .scheduler(com.github.benmanes.caffeine.cache.Scheduler.systemScheduler())
            .expireAfter(new Expiry<String, CacheEntry>() {
                @Override
                public long expireAfterCreate(String key, CacheEntry entry, long currentTime) {
                    return entry.ttlNanos();
                }

                @Override
                public long expireAfterUpdate(String key, CacheEntry entry, long currentTime, long currentDuration) {
                    return entry.ttlNanos();
                }

                @Override
                public long expireAfterRead(String key, CacheEntry entry, long currentTime, long currentDuration) {
                    // Recalculate remaining TTL on read to handle time-based expiry
                    return entry.ttlNanos();
                }
            })
            .recordStats()
            .build();
        this.l2Connection = valkey;
        this.maxTtl = maxTtl;
        this.inflight = new ConcurrentHashMap<>();
        this.l1Hits = 0;
        this.l2Hits = 0;
        this.misses = 0;

        // Register cache size gauge with metrics
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().setCacheSizeSupplier(this::size);
        }
    }

    /**
     * Get filtered metadata from cache, or compute if missing.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     * @param loader Function to compute filtered metadata and earliest blockedUntil on cache miss
     * @return CompletableFuture with filtered metadata bytes
     */
    public CompletableFuture<byte[]> get(
        final String repoType,
        final String repoName,
        final String packageName,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        final String key = cacheKey(repoType, repoName, packageName);

        // L1 check - also verify entry hasn't expired
        final CacheEntry l1Cached = this.l1Cache.getIfPresent(key);
        if (l1Cached != null) {
            // Check if entry has expired (blockedUntil has passed)
            if (l1Cached.isExpired()) {
                // Entry expired - invalidate and reload
                this.l1Cache.invalidate(key);
            } else {
                this.l1Hits++;
                if (CooldownMetrics.isAvailable()) {
                    CooldownMetrics.getInstance().recordCacheHit("l1");
                }
                return CompletableFuture.completedFuture(l1Cached.data());
            }
        }

        // L2 check (if available)
        if (this.l2Connection != null) {
            return this.l2Connection.async().get(key)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> null)
                .thenCompose(l2Bytes -> {
                    if (l2Bytes != null) {
                        this.l2Hits++;
                        if (CooldownMetrics.isAvailable()) {
                            CooldownMetrics.getInstance().recordCacheHit("l2");
                        }
                        // Promote to L1 with remaining TTL from L2
                        // Note: L2 doesn't store TTL info, so use max TTL for promoted entries
                        final CacheEntry entry = new CacheEntry(l2Bytes, Optional.empty(), this.maxTtl);
                        this.l1Cache.put(key, entry);
                        return CompletableFuture.completedFuture(l2Bytes);
                    }
                    // Miss - load and cache
                    this.misses++;
                    if (CooldownMetrics.isAvailable()) {
                        CooldownMetrics.getInstance().recordCacheMiss();
                    }
                    return this.loadAndCache(key, loader);
                });
        }

        // Single-tier: load and cache
        this.misses++;
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordCacheMiss();
        }
        return this.loadAndCache(key, loader);
    }

    /**
     * Load metadata and cache in both tiers with dynamic TTL.
     * Uses single-flight pattern to prevent stampede.
     */
    private CompletableFuture<byte[]> loadAndCache(
        final String key,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        // Check if already loading (stampede prevention)
        final CompletableFuture<CacheEntry> existing = this.inflight.get(key);
        if (existing != null) {
            return existing.thenApply(CacheEntry::data);
        }

        // Start loading
        final CompletableFuture<CacheEntry> future = loader.get()
            .whenComplete((entry, error) -> {
                this.inflight.remove(key);
                if (error == null && entry != null) {
                    // Cache in L1 with dynamic TTL
                    this.l1Cache.put(key, entry);
                    // Cache in L2 with dynamic TTL
                    if (this.l2Connection != null) {
                        final long ttlSeconds = entry.ttlSeconds();
                        if (ttlSeconds > 0) {
                            this.l2Connection.async().setex(key, ttlSeconds, entry.data());
                        }
                    }
                }
            });

        this.inflight.put(key, future);
        return future.thenApply(CacheEntry::data);
    }

    /**
     * Invalidate cached metadata for a package.
     * Called when a version is blocked or unblocked.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     */
    public void invalidate(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        final String key = cacheKey(repoType, repoName, packageName);
        this.l1Cache.invalidate(key);
        this.inflight.remove(key);
        if (this.l2Connection != null) {
            this.l2Connection.async().del(key);
        }
    }

    /**
     * Invalidate all cached metadata for a repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void invalidateAll(final String repoType, final String repoName) {
        final String prefix = "metadata:" + repoType + ":" + repoName + ":";

        // L1: Invalidate matching keys
        this.l1Cache.asMap().keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .forEach(key -> {
                this.l1Cache.invalidate(key);
                this.inflight.remove(key);
            });

        // Also clear any inflight requests for this repo
        this.inflight.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .forEach(this.inflight::remove);

        // L2: Pattern delete (expensive but rare)
        if (this.l2Connection != null) {
            this.l2Connection.async().keys(prefix + "*")
                .thenAccept(keys -> {
                    if (keys != null && !keys.isEmpty()) {
                        this.l2Connection.async().del(keys.toArray(new String[0]));
                    }
                });
        }
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        this.l1Cache.invalidateAll();
        this.inflight.clear();
        this.l1Hits = 0;
        this.l2Hits = 0;
        this.misses = 0;
    }

    /**
     * Get cache statistics.
     *
     * @return Statistics string
     */
    public String stats() {
        final long total = this.l1Hits + this.l2Hits + this.misses;
        if (total == 0) {
            return "FilteredMetadataCache[empty]";
        }
        final double hitRate = 100.0 * (this.l1Hits + this.l2Hits) / total;
        return String.format(
            "FilteredMetadataCache[size=%d, l1Hits=%d, l2Hits=%d, misses=%d, hitRate=%.1f%%]",
            this.l1Cache.estimatedSize(),
            this.l1Hits,
            this.l2Hits,
            this.misses,
            hitRate
        );
    }

    /**
     * Get estimated cache size.
     *
     * @return Number of cached entries in L1
     */
    public long size() {
        return this.l1Cache.estimatedSize();
    }

    /**
     * Force cleanup of expired entries.
     * Caffeine doesn't actively evict entries - this forces a check.
     * Primarily useful for testing.
     */
    public void cleanUp() {
        this.l1Cache.cleanUp();
    }

    /**
     * Generate cache key.
     */
    private String cacheKey(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        return String.format("metadata:%s:%s:%s", repoType, repoName, packageName);
    }

    /**
     * Cache entry with filtered metadata and dynamic TTL.
     * TTL is calculated based on the earliest blockedUntil timestamp.
     */
    public static final class CacheEntry {
        private final byte[] data;
        private final Optional<Instant> earliestBlockedUntil;
        private final Duration maxTtl;
        private final Instant createdAt;

        /**
         * Constructor.
         *
         * @param data Filtered metadata bytes
         * @param earliestBlockedUntil Earliest blockedUntil among blocked versions (empty if none blocked)
         * @param maxTtl Maximum TTL when no versions are blocked
         */
        public CacheEntry(
            final byte[] data,
            final Optional<Instant> earliestBlockedUntil,
            final Duration maxTtl
        ) {
            this.data = data;
            this.earliestBlockedUntil = earliestBlockedUntil;
            this.maxTtl = maxTtl;
            this.createdAt = Instant.now();
        }

        /**
         * Get filtered metadata bytes.
         *
         * @return Metadata bytes
         */
        public byte[] data() {
            return this.data;
        }

        /**
         * Get earliest blockedUntil timestamp.
         *
         * @return Earliest blockedUntil or empty if no versions blocked
         */
        public Optional<Instant> earliestBlockedUntil() {
            return this.earliestBlockedUntil;
        }

        /**
         * Check if this entry has expired.
         * An entry is expired if blockedUntil has passed.
         *
         * @return true if expired
         */
        public boolean isExpired() {
            if (this.earliestBlockedUntil.isPresent()) {
                return Instant.now().isAfter(this.earliestBlockedUntil.get());
            }
            // No blocked versions - check if max TTL has passed since creation
            return Duration.between(this.createdAt, Instant.now()).compareTo(this.maxTtl) > 0;
        }

        /**
         * Calculate TTL in nanoseconds for Caffeine expiry.
         * If versions are blocked: TTL = earliestBlockedUntil - now
         * If no versions blocked: TTL = maxTtl (release dates don't change)
         *
         * @return TTL in nanoseconds
         */
        public long ttlNanos() {
            if (this.earliestBlockedUntil.isPresent()) {
                final Duration remaining = Duration.between(Instant.now(), this.earliestBlockedUntil.get());
                if (remaining.isNegative() || remaining.isZero()) {
                    // Already expired - use minimum TTL
                    return MIN_TTL.toNanos();
                }
                return remaining.toNanos();
            }
            // No blocked versions - cache for max TTL
            return this.maxTtl.toNanos();
        }

        /**
         * Calculate TTL in seconds for L2 cache.
         *
         * @return TTL in seconds
         */
        public long ttlSeconds() {
            return Math.max(MIN_TTL.getSeconds(), this.ttlNanos() / 1_000_000_000L);
        }

        /**
         * Create entry for metadata with no blocked versions.
         * Uses maximum TTL since release dates don't change.
         *
         * @param data Filtered metadata bytes
         * @param maxTtl Maximum TTL
         * @return Cache entry
         */
        public static CacheEntry noBlockedVersions(final byte[] data, final Duration maxTtl) {
            return new CacheEntry(data, Optional.empty(), maxTtl);
        }

        /**
         * Create entry for metadata with blocked versions.
         * TTL is set to expire when the earliest block expires.
         *
         * @param data Filtered metadata bytes
         * @param earliestBlockedUntil When the earliest block expires
         * @param maxTtl Maximum TTL (used as fallback)
         * @return Cache entry
         */
        public static CacheEntry withBlockedVersions(
            final byte[] data,
            final Instant earliestBlockedUntil,
            final Duration maxTtl
        ) {
            return new CacheEntry(data, Optional.of(earliestBlockedUntil), maxTtl);
        }
    }
}
