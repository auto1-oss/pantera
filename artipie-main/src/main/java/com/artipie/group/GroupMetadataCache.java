/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.cache.GlobalCacheConfig;
import com.artipie.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Two-tier cache for Maven group merged metadata with configurable TTL.
 * 
 * <p>Key format: {@code maven:group:metadata:{group_name}:{path}}</p>
 * 
 * <p>Architecture:</p>
 * <ul>
 *   <li>L1 (Caffeine): Fast in-memory cache, short TTL when L2 enabled</li>
 *   <li>L2 (Valkey/Redis): Distributed cache, full TTL</li>
 * </ul>
 *
 * @since 1.0
 */
public final class GroupMetadataCache {

    /**
     * Default TTL (same as Maven proxy metadata: 12 hours).
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(12);

    /**
     * Default max size for L1 cache.
     */
    private static final int DEFAULT_MAX_SIZE = 1000;

    /**
     * L1 cache (in-memory).
     */
    private final Cache<String, CachedMetadata> l1Cache;

    /**
     * L2 cache (Valkey/Redis), may be null.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;

    /**
     * TTL for cached metadata.
     */
    private final Duration ttl;

    /**
     * Group repository name.
     */
    private final String groupName;

    /**
     * Last-known-good metadata (never expires).
     * Populated on every successful put(), survives L1/L2 invalidation/expiry.
     * Used as stale fallback when upstream is unreachable.
     */
    private final ConcurrentMap<String, byte[]> lastKnownGood;

    /**
     * Create group metadata cache with defaults.
     * @param groupName Group repository name
     */
    public GroupMetadataCache(final String groupName) {
        this(groupName, DEFAULT_TTL, DEFAULT_MAX_SIZE, null);
    }

    /**
     * Create group metadata cache with custom parameters.
     * @param groupName Group repository name
     * @param ttl Time-to-live for cached metadata
     * @param maxSize Maximum L1 cache size
     * @param valkey Optional Valkey connection for L2
     */
    public GroupMetadataCache(
        final String groupName,
        final Duration ttl,
        final int maxSize,
        final ValkeyConnection valkey
    ) {
        this.groupName = groupName;
        this.ttl = ttl;

        // Check global config if no explicit valkey passed
        final ValkeyConnection actualValkey = (valkey != null)
            ? valkey
            : GlobalCacheConfig.valkeyConnection().orElse(null);

        this.twoTier = (actualValkey != null);
        this.l2 = this.twoTier ? actualValkey.async() : null;

        // L1 cache: shorter TTL when L2 enabled (5 min), full TTL otherwise
        final Duration l1Ttl = this.twoTier ? Duration.ofMinutes(5) : ttl;
        final int l1Size = this.twoTier ? Math.max(100, maxSize / 10) : maxSize;

        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        this.lastKnownGood = new ConcurrentHashMap<>();
    }

    /**
     * Build L2 cache key.
     * Format: maven:group:metadata:{group_name}:{path}
     */
    private String buildL2Key(final String path) {
        return "maven:group:metadata:" + this.groupName + ":" + path;
    }

    /**
     * Get cached metadata (checks L1, then L2 if miss).
     * @param path Metadata path
     * @return Optional containing cached bytes, or empty if not found
     */
    public CompletableFuture<Optional<byte[]>> get(final String path) {
        // Check L1 first
        final CachedMetadata cached = this.l1Cache.getIfPresent(path);
        if (cached != null && !isExpired(cached)) {
            recordCacheHit("l1");
            return CompletableFuture.completedFuture(Optional.of(cached.data));
        }
        recordCacheMiss("l1");

        // Check L2 if available
        if (!this.twoTier) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final String l2Key = buildL2Key(path);
        return this.l2.get(l2Key)
            .toCompletableFuture()
            .orTimeout(100, TimeUnit.MILLISECONDS)
            .exceptionally(err -> null)
            .thenApply(bytes -> {
                if (bytes != null && bytes.length > 0) {
                    // L2 HIT - promote to L1
                    final CachedMetadata entry = new CachedMetadata(bytes, Instant.now());
                    this.l1Cache.put(path, entry);
                    recordCacheHit("l2");
                    return Optional.of(bytes);
                }
                recordCacheMiss("l2");
                return Optional.empty();
            });
    }

    /**
     * Get stale (last-known-good) metadata. This data never expires and is
     * populated on every successful {@link #put}. Use as fallback when all
     * group members are unreachable and the primary cache has expired.
     * @param path Metadata path
     * @return Optional containing last-known-good bytes, or empty if never cached
     */
    public CompletableFuture<Optional<byte[]>> getStale(final String path) {
        final byte[] data = this.lastKnownGood.get(path);
        if (data != null) {
            recordCacheHit("lkg");
            return CompletableFuture.completedFuture(Optional.of(data));
        }
        recordCacheMiss("lkg");
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Put metadata in cache (both L1 and L2).
     * @param path Metadata path
     * @param data Metadata bytes
     */
    public void put(final String path, final byte[] data) {
        // Always update last-known-good (never expires)
        this.lastKnownGood.put(path, data);
        // Put in L1
        final CachedMetadata entry = new CachedMetadata(data, Instant.now());
        this.l1Cache.put(path, entry);

        // Put in L2 if available
        if (this.twoTier) {
            final String l2Key = buildL2Key(path);
            this.l2.setex(l2Key, this.ttl.getSeconds(), data);
        }
    }

    /**
     * Invalidate cached metadata.
     * @param path Metadata path
     */
    public void invalidate(final String path) {
        this.l1Cache.invalidate(path);
        if (this.twoTier) {
            this.l2.del(buildL2Key(path));
        }
    }

    /**
     * Check if cached entry is expired.
     */
    private boolean isExpired(final CachedMetadata cached) {
        return cached.cachedAt.plus(this.ttl).isBefore(Instant.now());
    }

    /**
     * Record cache hit metric.
     */
    private void recordCacheHit(final String tier) {
        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            com.artipie.metrics.MicrometerMetrics.getInstance()
                .recordCacheHit("maven_group_metadata", tier);
        }
    }

    /**
     * Record cache miss metric.
     */
    private void recordCacheMiss(final String tier) {
        if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
            com.artipie.metrics.MicrometerMetrics.getInstance()
                .recordCacheMiss("maven_group_metadata", tier);
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

    /**
     * Cached metadata entry with timestamp.
     */
    private record CachedMetadata(byte[] data, Instant cachedAt) { }
}

