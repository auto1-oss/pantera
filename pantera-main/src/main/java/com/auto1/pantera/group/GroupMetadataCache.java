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
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry;
import com.auto1.pantera.maven.cooldown.MavenMetadataCoordinates;
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
 * Cache of a Maven group's winning-member {@code maven-metadata.xml}
 * (already cooldown-filtered by the proxy member), keyed by request path.
 *
 * <p>Architecture:</p>
 * <ul>
 *   <li>Primary (Caffeine, per node): the bytes served on a hit. TTL
 *       {@link #DEFAULT_TTL} = 10 min, the same as the cooldown
 *       filtered-metadata envelope's L2 TTL.</li>
 *   <li>Stale L1 (Caffeine): Last-known-good, long TTL, bounded size</li>
 *   <li>Stale L2 (Valkey/Redis): Last-known-good distributed, key
 *       {@code maven:group:metadata:stale:{group_name}:{path}}</li>
 * </ul>
 *
 * <p>Cooldown coherence: the primary tier holds cooldown-FILTERED bytes, so
 * it must follow block / unblock / expiry / refresh / upload events. Each
 * instance registers a
 * {@link FilteredMetadataCacheRegistry.PackageListener} (held weakly by
 * the registry under a per-instance owner id, so every live cache of a
 * group is notified and a discarded one drops out)
 * that drops every primary entry whose path maps to the changed dotted
 * package — artifact-level and snapshot-level metadata alike — and drops
 * everything on a policy / repo-wide change. The registry delivers these
 * events on every node (pub/sub), so each node's primary tier is cleared.
 * Natural block expiry emits no event while the group serves from cache;
 * the 10-minute primary TTL bounds how long an expired block stays
 * invisible.</p>
 *
 * <p>There is deliberately NO distributed (Valkey) primary tier: it could
 * not be invalidated per package without an unbounded key scan (paths map
 * to packages only one way — dotted &rarr; slashed is ambiguous because
 * artifactIds may contain dots), so it would re-promote pre-unblock bytes
 * into every node's L1 after the event had cleared them. A primary miss
 * costs one member walk, which the member proxy answers from its own
 * metadata + filtered-envelope caches (those ARE cluster-wide and
 * event-invalidated).</p>
 *
 * <p>The stale tier is NOT invalidated by package events: it is only read
 * when every member failed, and a member's cooldown verdict is relayed by
 * {@link MavenGroupSlice} before the stale tier is ever consulted.</p>
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
     * Default primary TTL: 10 minutes, matching the cooldown
     * filtered-metadata envelope L2 TTL
     * ({@code FilteredMetadataCacheConfig.DEFAULT_L2_TTL}) so a block that
     * expires naturally (no invalidation event) becomes visible through the
     * group within the same bound as through the proxy.
     */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /**
     * Default max size for L1 cache.
     */
    private static final int DEFAULT_MAX_SIZE = 1000;

    /**
     * L1 cache (in-memory) — PRIMARY tier.
     */
    private final Cache<String, CachedMetadata> l1Cache;

    /**
     * Cooldown package-event listener; strongly held here because the
     * registry only keeps a weak reference.
     */
    private final PrimaryInvalidator invalidator;

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
     * @param valkey Optional Valkey connection for the stale L2 tier
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

        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
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
        // The registry holds listeners weakly; this field is the strong
        // reference that keeps events flowing while this cache is alive.
        // Owner id is per instance so two live caches of one group (e.g. the
        // group slice built for two ports) are both invalidated.
        this.invalidator = new PrimaryInvalidator(this.l1Cache);
        FilteredMetadataCacheRegistry.instance().addPackageListener(
            "maven-group:" + groupName + "@" + System.identityHashCode(this),
            this.invalidator
        );
    }

    /**
     * Build stale L2 cache key.
     * Format: {@code maven:group:metadata:stale:{group_name}:{path}}
     */
    private String buildStaleL2Key(final String path) {
        return "maven:group:metadata:stale:" + this.groupName + ":" + path;
    }

    /**
     * Get cached metadata from the primary tier.
     * @param path Metadata path
     * @return Optional containing cached bytes, or empty if not found
     */
    public CompletableFuture<Optional<byte[]>> get(final String path) {
        final CachedMetadata cached = this.l1Cache.getIfPresent(path);
        if (cached != null && !isExpired(cached)) {
            recordCacheHit("l1");
            return CompletableFuture.completedFuture(Optional.of(cached.data));
        }
        recordCacheMiss("l1");
        return CompletableFuture.completedFuture(Optional.empty());
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
     * Put metadata in cache (primary and stale L1+L2).
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
        this.l1Cache.put(path, new CachedMetadata(data, Instant.now()));
    }

    /**
     * Invalidate cached metadata in the PRIMARY tier only.
     * The stale (last-known-good) tier is deliberately preserved so
     * callers can still serve fallback after primary invalidation.
     * @param path Metadata path
     */
    public void invalidate(final String path) {
        this.l1Cache.invalidate(path);
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
     * Cached metadata entry with timestamp.
     */
    private record CachedMetadata(byte[] data, Instant cachedAt) { }

    /**
     * Drops primary entries when the cooldown-filtered view of a package may
     * have changed. Maps each cached PATH to its dotted package and compares
     * (never dotted &rarr; path, which is ambiguous).
     */
    private static final class PrimaryInvalidator
        implements FilteredMetadataCacheRegistry.PackageListener {

        /**
         * Primary tier to invalidate.
         */
        private final Cache<String, CachedMetadata> primary;

        /**
         * Path &rarr; package mapping shared with the proxy.
         */
        private final MavenMetadataCoordinates coords;

        /**
         * Ctor.
         * @param primary Primary tier
         */
        PrimaryInvalidator(final Cache<String, CachedMetadata> primary) {
            this.primary = primary;
            this.coords = new MavenMetadataCoordinates();
        }

        @Override
        public void packageChanged(final String packageName) {
            this.primary.asMap().keySet().removeIf(
                path -> this.coords.packageName(path)
                    .map(packageName::equals)
                    .orElse(false)
            );
        }

        @Override
        public void allChanged() {
            this.primary.invalidateAll();
        }
    }
}
