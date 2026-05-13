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
package com.auto1.pantera.group;

import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-tier cache for Maven group merged metadata with configurable TTL.
 *
 * <p>Key format: {@code maven:group:metadata:{group_name}:{path}}</p>
 *
 * <p>Architecture:</p>
 * <ul>
 *   <li>L1 (Caffeine): Fast in-memory primary cache, short TTL when L2 enabled</li>
 *   <li>L2 (Valkey/Redis): Distributed primary cache, full TTL</li>
 *   <li>Stale L1 (Caffeine): Last-known-good, long TTL, bounded size</li>
 *   <li>Stale L2 (Valkey/Redis): Last-known-good distributed, key
 *       {@code maven:group:metadata:stale:{group_name}:{path}}</li>
 * </ul>
 *
 * <p>Design principle for the STALE tier: it is an AID, never a BREAKER.
 * Under realistic cardinality no eviction ever fires. Bounds are a
 * JVM-memory safety net against pathological growth — not an expiry
 * mechanism. {@link #getStaleWithFallback} degrades gracefully:
 * stale-L1 &rarr; stale-L2 &rarr; expired-primary-L1 &rarr; miss.
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
     * L1 cache (in-memory) — PRIMARY tier.
     */
    private final Cache<String, CachedMetadata> l1Cache;

    /**
     * L2 cache (Valkey/Redis), may be null — PRIMARY tier.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether two-tier caching is enabled (primary).
     */
    private final boolean twoTier;

    /**
     * TTL for cached metadata (primary).
     */
    private final Duration ttl;

    /**
     * Group repository name.
     */
    private final String groupName;

    /**
     * Stale L1 — last-known-good in-memory. Long TTL (30d default),
     * bounded size as a JVM-memory safety net only.
     */
    private final Cache<String, byte[]> lastKnownGoodL1;

    /**
     * Whether stale two-tier caching is enabled.
     */
    private final boolean staleTwoTier;

    /**
     * Stale L2 — last-known-good in Valkey, may be null.
     * Uses the same shared connection pool as the primary L2; keys are
     * namespaced with a {@code stale:} segment to avoid collision.
     */
    private final RedisAsyncCommands<String, byte[]> staleL2;

    /**
     * Timeout for stale L2 reads.
     */
    private final Duration staleL2Timeout;

    /**
     * Stale L2 TTL in seconds. {@code 0} = no TTL (rely on Valkey LRU).
     */
    private final long staleL2TtlSeconds;

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

        // -------------------------------------------------------------
        // Stale (last-known-good) tier — aid, not breaker.
        // -------------------------------------------------------------
        final GlobalCacheConfig.GroupMetadataStaleConfig sc =
            GlobalCacheConfig.getInstance().groupMetadataStale();
        this.staleTwoTier = sc.l2Enabled() && actualValkey != null;
        this.lastKnownGoodL1 = Caffeine.newBuilder()
            .maximumSize(sc.l1MaxSize())
            .expireAfterWrite(Duration.ofSeconds(sc.l1TtlSeconds()))
            .recordStats()
            .build();
        this.staleL2 = this.staleTwoTier ? actualValkey.async() : null;
        this.staleL2Timeout = Duration.ofMillis(sc.l2TimeoutMs());
        this.staleL2TtlSeconds = sc.l2TtlSeconds();
    }

    /**
     * Build primary L2 cache key.
     * Format: {@code maven:group:metadata:{group_name}:{path}}
     */
    private String buildL2Key(final String path) {
        return "maven:group:metadata:" + this.groupName + ":" + path;
    }

    /**
     * Build stale L2 cache key.
     * Format: {@code maven:group:metadata:stale:{group_name}:{path}}
     */
    private String buildStaleL2Key(final String path) {
        return "maven:group:metadata:stale:" + this.groupName + ":" + path;
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
     * Get stale (last-known-good) metadata with graceful 3-step fallback:
     * stale-L1 &rarr; stale-L2 &rarr; expired-primary-L1 &rarr; miss.
     *
     * <p>This path is the BREAKER's fallback (the "aid") and never throws.
     * @param path Metadata path
     * @return Optional containing last-known-good bytes, or empty if not found
     */
    public CompletableFuture<Optional<byte[]>> getStaleWithFallback(final String path) {
        // 1. Stale L1
        final byte[] l1 = this.lastKnownGoodL1.getIfPresent(path);
        if (l1 != null) {
            recordStaleServedFrom("l1");
            return CompletableFuture.completedFuture(Optional.of(l1));
        }
        // 2. Stale L2 (bounded by staleL2Timeout, fully defensive)
        final CompletableFuture<Optional<byte[]>> l2Future;
        if (this.staleTwoTier) {
            l2Future = this.staleL2.get(buildStaleL2Key(path))
                .toCompletableFuture()
                .orTimeout(this.staleL2Timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(err -> null)
                .thenApply(b -> b != null && b.length > 0
                    ? Optional.of(b)
                    : Optional.<byte[]>empty());
        } else {
            l2Future = CompletableFuture.completedFuture(Optional.<byte[]>empty());
        }
        return l2Future.thenApply(l2hit -> {
            if (l2hit.isPresent()) {
                // Promote stale-L2 to stale-L1 so subsequent reads are local.
                this.lastKnownGoodL1.put(path, l2hit.get());
                recordStaleServedFrom("l2");
                return l2hit;
            }
            // 3. Last resort: expired primary-cache entry (peek past TTL)
            final byte[] expired = peekExpiredPrimary(path);
            if (expired != null) {
                recordStaleServedFrom("expired-primary");
                return Optional.of(expired);
            }
            recordStaleServedFrom("miss");
            return Optional.<byte[]>empty();
        });
    }

    /**
     * Backward-compatible alias for {@link #getStaleWithFallback(String)}.
     *
     * @param path Metadata path
     * @return Optional containing last-known-good bytes, or empty if not found
     * @deprecated Use {@link #getStaleWithFallback(String)} — this alias
     *     exists to keep existing call sites compiling across the
     *     2-tier-stale migration and will be removed in a future release.
     */
    @Deprecated
    public CompletableFuture<Optional<byte[]>> getStale(final String path) {
        return getStaleWithFallback(path);
    }

    /**
     * Peek the primary L1 cache past its TTL. Caffeine's
     * {@code getIfPresent} drops expired entries, but {@code asMap().get()}
     * returns entries that are technically past their write TTL but have
     * not yet been swept by Caffeine's cleanup thread. This is documented
     * as a "close-enough" last-resort fallback for the stale path — see
     * {@code docs/superpowers/plans/2026-04-19-v2.2-production-readiness-A-H.md}
     * Group C. We did NOT use {@code Policy.getIfPresentQuietly} because its
     * expiration semantics on 3.2.3 are not guaranteed to return already-
     * expired entries; {@code asMap} is the explicit, well-known workaround.
     *
     * @param path Metadata path
     * @return Raw bytes if still present in the primary map (even if past
     *     TTL), or {@code null}
     */
    private byte[] peekExpiredPrimary(final String path) {
        final CachedMetadata cached = this.l1Cache.asMap().get(path);
        return cached != null ? cached.data : null;
    }

    /**
     * Put metadata in cache (both primary L1+L2 and stale L1+L2).
     * @param path Metadata path
     * @param data Metadata bytes
     */
    public void put(final String path, final byte[] data) {
        // Always update last-known-good (stale tier).
        this.lastKnownGoodL1.put(path, data);
        if (this.staleTwoTier) {
            final String staleKey = buildStaleL2Key(path);
            if (this.staleL2TtlSeconds > 0) {
                this.staleL2.set(
                    staleKey,
                    data,
                    SetArgs.Builder.ex(this.staleL2TtlSeconds)
                );
            } else {
                // 0 = no TTL, rely on Valkey LRU
                this.staleL2.set(staleKey, data);
            }
        }
        // Primary L1
        final CachedMetadata entry = new CachedMetadata(data, Instant.now());
        this.l1Cache.put(path, entry);

        // Primary L2 if available
        if (this.twoTier) {
            final String l2Key = buildL2Key(path);
            this.l2.setex(l2Key, this.ttl.getSeconds(), data);
        }
    }

    /**
     * Invalidate cached metadata in the PRIMARY tier only.
     * The stale (last-known-good) tier is deliberately preserved so
     * callers can still serve fallback after primary invalidation.
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
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheHit("maven_group_metadata", tier);
        }
    }

    /**
     * Record cache miss metric.
     */
    private void recordCacheMiss(final String tier) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheMiss("maven_group_metadata", tier);
        }
    }

    /**
     * Record which tier served a stale fallback read.
     * Values: {@code l1}, {@code l2}, {@code expired-primary}, {@code miss}.
     * Reuses the existing cache-requests counter surface so no new
     * Micrometer meter needs to be registered; tier labels are prefixed
     * with {@code stale-} to disambiguate from primary tiers.
     */
    private void recordStaleServedFrom(final String tier) {
        if ("miss".equals(tier)) {
            recordCacheMiss("stale-" + tier);
        } else {
            recordCacheHit("stale-" + tier);
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
