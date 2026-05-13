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
package com.auto1.pantera.cooldown.metadata;

import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.cooldown.metrics.CooldownMetrics;

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
 *   <li>L2-only mode: Set l1MaxSize=0 in config to disable L1 (for large metadata)</li>
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
 * <p>Configuration via YAML (pantera.yaml):</p>
 * <pre>
 * meta:
 *   caches:
 *     cooldown-metadata:
 *       ttl: 24h
 *       maxSize: 5000
 *       valkey:
 *         enabled: true
 *         l1MaxSize: 500   # 0 for L2-only mode
 *         l1Ttl: 5m
 *         l2Ttl: 24h
 * </pre>
 *
 * @since 1.0
 */
public class FilteredMetadataCache {

    /**
     * Default L1 cache size (number of packages).
     * Configurable via {@code PANTERA_COOLDOWN_METADATA_L1_SIZE} env var.
     */
    private static final int DEFAULT_L1_SIZE = resolveDefaultL1Size();

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
     * Grace period after logical TTL expiry during which the stale entry
     * remains in Caffeine to serve stale-while-revalidate responses (H3).
     */
    private static final Duration SWR_GRACE = Duration.ofMinutes(5);

    /**
     * L1 cache (in-memory) with per-entry dynamic TTL.
     * May be null in L2-only mode.
     */
    private final Cache<String, CacheEntry> l1Cache;

    /**
     * Whether L2-only mode is enabled (no L1 cache).
     */
    private final boolean l2OnlyMode;

    /**
     * L2 cache connection (Valkey/Redis), may be null.
     */
    private final ValkeyConnection l2Connection;

    /**
     * L1 cache TTL (max TTL for in-memory entries).
     */
    private final Duration l1Ttl;

    /**
     * L2 cache TTL (max TTL for Valkey entries).
     */
    private final Duration l2Ttl;

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
        this(DEFAULT_L1_SIZE, DEFAULT_MAX_TTL, DEFAULT_MAX_TTL, null);
    }

    /**
     * Constructor with Valkey connection.
     *
     * @param valkey Valkey connection for L2 cache
     */
    public FilteredMetadataCache(final ValkeyConnection valkey) {
        this(DEFAULT_L1_SIZE, DEFAULT_MAX_TTL, DEFAULT_MAX_TTL, valkey);
    }

    /**
     * Constructor from configuration.
     *
     * @param config Cache configuration
     * @param valkey Valkey connection for L2 cache (null for single-tier)
     */
    public FilteredMetadataCache(
        final FilteredMetadataCacheConfig config,
        final ValkeyConnection valkey
    ) {
        this(
            config.isValkeyEnabled() ? config.l1MaxSize() : config.maxSize(),
            config.isValkeyEnabled() ? config.l1Ttl() : config.ttl(),
            config.isValkeyEnabled() ? config.l2Ttl() : config.ttl(),
            valkey
        );
    }

    /**
     * Full constructor.
     *
     * @param l1Size Maximum L1 cache size (0 for L2-only mode)
     * @param l1Ttl L1 cache TTL
     * @param l2Ttl L2 cache TTL
     * @param valkey Valkey connection (null for single-tier)
     */
    public FilteredMetadataCache(
        final int l1Size,
        final Duration l1Ttl,
        final Duration l2Ttl,
        final ValkeyConnection valkey
    ) {
        // L2-only mode: l1Size == 0 AND valkey is available
        this.l2OnlyMode = (l1Size == 0 && valkey != null);
        
        // L1 cache with dynamic per-entry expiration based on blockedUntil
        // Skip L1 cache creation in L2-only mode
        if (this.l2OnlyMode) {
            this.l1Cache = null;
        } else {
            // Use scheduler for more timely eviction of expired entries
            this.l1Cache = Caffeine.newBuilder()
                .maximumSize(l1Size > 0 ? l1Size : DEFAULT_L1_SIZE)
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
        }
        this.l2Connection = valkey;
        this.l1Ttl = l1Ttl;
        this.l2Ttl = l2Ttl;
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

        // L1 check - skip in L2-only mode
        if (!this.l2OnlyMode && this.l1Cache != null) {
            final CacheEntry l1Cached = this.l1Cache.getIfPresent(key);
            if (l1Cached != null) {
                if (l1Cached.isExpired()) {
                    // Stale-while-revalidate (H3): return stale bytes immediately
                    // and trigger background re-evaluation so the next caller gets
                    // fresh data without waiting.
                    this.triggerBackgroundRevalidation(key, loader);
                    this.l1Hits++;
                    if (CooldownMetrics.isAvailable()) {
                        CooldownMetrics.getInstance().recordCacheHit("l1_swr");
                    }
                    return CompletableFuture.completedFuture(l1Cached.data());
                }
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
                        // Promote to L1 with remaining TTL from L2 (skip in L2-only mode)
                        if (!this.l2OnlyMode && this.l1Cache != null) {
                            // Note: L2 doesn't store TTL info, so use L1 TTL for promoted entries
                            final CacheEntry entry = new CacheEntry(l2Bytes, Optional.empty(), this.l1Ttl);
                            this.l1Cache.put(key, entry);
                        }
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
     * Trigger background re-evaluation for a stale cache entry (SWR — H3).
     * Only fires if no revalidation is already in progress for this key.
     * The caller has already returned stale bytes to the client.
     */
    private void triggerBackgroundRevalidation(
        final String key,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        if (this.inflight.containsKey(key)) {
            // Already revalidating — skip duplicate
            return;
        }
        // Fire-and-forget: loadAndCache will update L1 + L2 on completion
        this.loadAndCache(key, loader);
    }

    /**
     * Load metadata and cache in both tiers with dynamic TTL.
     * Uses single-flight pattern to prevent stampede.
     * Registers in inflight BEFORE attaching whenComplete to avoid the
     * same race condition fixed in CooldownCache (H5).
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

        // Start loading -- register in inflight BEFORE whenComplete
        final CompletableFuture<CacheEntry> future = loader.get();
        this.inflight.put(key, future);
        future.whenComplete((entry, error) -> {
            this.inflight.remove(key);
            if (error == null && entry != null) {
                // Cache in L1 with L1 TTL (skip in L2-only mode)
                if (!this.l2OnlyMode && this.l1Cache != null) {
                    // Wrap entry with L1 TTL for proper expiration
                    final CacheEntry l1Entry = new CacheEntry(
                        entry.data(),
                        entry.earliestBlockedUntil(),
                        this.l1Ttl
                    );
                    this.l1Cache.put(key, l1Entry);
                }
                // Cache in L2 with L2 TTL (use configured l2Ttl, capped by blockedUntil if present)
                if (this.l2Connection != null) {
                    final long ttlSeconds = this.calculateL2Ttl(entry);
                    if (ttlSeconds > 0) {
                        this.l2Connection.async().setex(key, ttlSeconds, entry.data());
                    }
                }
            }
        });

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
        if (this.l1Cache != null) {
            this.l1Cache.invalidate(key);
        }
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

        // L1: Invalidate matching keys (skip in L2-only mode)
        if (this.l1Cache != null) {
            this.l1Cache.asMap().keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> {
                    this.l1Cache.invalidate(key);
                    this.inflight.remove(key);
                });
        }

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
        if (this.l1Cache != null) {
            this.l1Cache.invalidateAll();
        }
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
            return this.l2OnlyMode 
                ? "FilteredMetadataCache[L2-only, empty]"
                : "FilteredMetadataCache[empty]";
        }
        final double hitRate = 100.0 * (this.l1Hits + this.l2Hits) / total;
        if (this.l2OnlyMode) {
            return String.format(
                "FilteredMetadataCache[L2-only, l2Hits=%d, misses=%d, hitRate=%.1f%%]",
                this.l2Hits,
                this.misses,
                hitRate
            );
        }
        return String.format(
            "FilteredMetadataCache[size=%d, l1Hits=%d, l2Hits=%d, misses=%d, hitRate=%.1f%%]",
            this.l1Cache != null ? this.l1Cache.estimatedSize() : 0,
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
        return this.l1Cache != null ? this.l1Cache.estimatedSize() : 0;
    }

    /**
     * Check if running in L2-only mode.
     *
     * @return True if L2-only mode is enabled
     */
    public boolean isL2OnlyMode() {
        return this.l2OnlyMode;
    }

    /**
     * Force cleanup of expired entries.
     * Caffeine doesn't actively evict entries - this forces a check.
     * Primarily useful for testing.
     */
    public void cleanUp() {
        if (this.l1Cache != null) {
            this.l1Cache.cleanUp();
        }
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
     * Calculate L2 TTL for a cache entry.
     * Uses the configured l2Ttl, but caps it by blockedUntil if present.
     *
     * @param entry Cache entry
     * @return TTL in seconds for L2 cache
     */
    private long calculateL2Ttl(final CacheEntry entry) {
        if (entry.earliestBlockedUntil().isPresent()) {
            // If versions are blocked, TTL = min(l2Ttl, time until earliest block expires)
            final Duration remaining = Duration.between(Instant.now(), entry.earliestBlockedUntil().get());
            if (remaining.isNegative() || remaining.isZero()) {
                return MIN_TTL.getSeconds();
            }
            // Use the smaller of remaining time and configured l2Ttl
            return Math.min(remaining.getSeconds(), this.l2Ttl.getSeconds());
        }
        // No blocked versions - use configured l2Ttl
        return this.l2Ttl.getSeconds();
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
            this.data = data; // NOPMD ArrayIsStoredDirectly - immutable cache value; defensive copy of filtered metadata bytes is wasteful
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
            return this.data; // NOPMD MethodReturnsInternalArray - immutable cache value; mirrors the matching ArrayIsStoredDirectly suppression on the ctor; callers treat this as read-only
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
         * Includes a SWR grace period so the entry stays in Caffeine
         * beyond its logical expiry, allowing stale-while-revalidate.
         * Use {@link #isExpired()} for logical expiry checks.
         *
         * @return TTL in nanoseconds (logical TTL + SWR grace)
         */
        public long ttlNanos() {
            if (this.earliestBlockedUntil.isPresent()) {
                final Duration remaining = Duration.between(Instant.now(), this.earliestBlockedUntil.get());
                if (remaining.isNegative() || remaining.isZero()) {
                    // Already logically expired - keep alive for SWR grace
                    return SWR_GRACE.toNanos();
                }
                return remaining.plus(SWR_GRACE).toNanos();
            }
            // No blocked versions - cache for max TTL + grace
            return this.maxTtl.plus(SWR_GRACE).toNanos();
        }

        /**
         * Calculate logical TTL in seconds for L2 cache (excludes SWR grace).
         *
         * @return TTL in seconds
         */
        public long ttlSeconds() {
            return Math.max(MIN_TTL.getSeconds(), this.logicalTtlNanos() / 1_000_000_000L);
        }

        /**
         * Logical TTL in nanoseconds (without SWR grace period).
         * Used for L2 TTL calculation and tests.
         *
         * @return Logical TTL in nanoseconds
         */
        private long logicalTtlNanos() {
            if (this.earliestBlockedUntil.isPresent()) {
                final Duration remaining = Duration.between(Instant.now(), this.earliestBlockedUntil.get());
                if (remaining.isNegative() || remaining.isZero()) {
                    return MIN_TTL.toNanos();
                }
                return remaining.toNanos();
            }
            return this.maxTtl.toNanos();
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

    /**
     * Resolve default L1 size from env var or fall back to 50,000 (H4).
     * Configurable via {@code PANTERA_COOLDOWN_METADATA_L1_SIZE}.
     *
     * @return L1 size
     */
    private static int resolveDefaultL1Size() {
        final String env = System.getenv("PANTERA_COOLDOWN_METADATA_L1_SIZE");
        if (env != null && !env.isEmpty()) {
            try {
                final int parsed = Integer.parseInt(env.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (final NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 50_000;
    }
}
