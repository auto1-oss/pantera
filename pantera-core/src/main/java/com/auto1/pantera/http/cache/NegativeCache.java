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

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.log.EcsLogger;
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
 * <p>Keyed by {@link NegativeCacheKey} ({@code scope:repoType:artifactName:artifactVersion}),
 * URL-encoded so embedded delimiters survive round-trips. Hosted, proxy, and group
 * scopes all share one L1 Caffeine + optional L2 Valkey bean.
 *
 * @since 0.11
 */
public final class NegativeCache {

    /**
     * Response header marking a {@code 404} whose absence is NOT authoritative
     * — e.g. a proxy member laundered an upstream rate-limit / non-404 4xx
     * ({@code 403}/{@code 429}/{@code 410}) into a {@code 404} so a multi-remote
     * race can fall through to the next remote. A group MUST NOT negative-cache
     * such a response: the artifact may well exist and the upstream was merely
     * throttling. Callers set this header on the synthetic 404; the group's
     * cache-decision skips {@link #cacheNotFound} when it is present (Fix 2).
     */
    public static final String SKIP_HEADER = "X-Pantera-Negative-Cache-Skip";

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
     * Cross-instance invalidation pubsub channel — null when running
     * single-node (no Valkey, or pub/sub disabled). Published-on every
     * call to {@link #invalidate}, {@link #invalidateByArtifactName},
     * and {@link #invalidateBatch}. Self-messages are filtered out by
     * {@link CacheInvalidationPubSub}'s instance-id; peer messages drop
     * the local L1 entry without re-publishing.
     */
    private final CacheInvalidationPubSub pubsub;

    /**
     * Pub/sub channel name used by {@link #pubsub}. Single-key
     * invalidations publish the {@link NegativeCacheKey#flat()} form;
     * {@link #invalidateByArtifactName} publishes the artifact name
     * prefixed with {@value #NAME_PREFIX} so receivers know to run
     * their own SCAN-and-match.
     */
    static final String PUBSUB_CHANNEL = "negative";

    /** Wire-prefix for an "invalidate by artifact name" peer message. */
    private static final String NAME_PREFIX = "name:";

    /**
     * Create negative cache from config (the single-instance wiring constructor).
     *
     * @param config Unified negative cache configuration
     */
    public NegativeCache(final NegativeCacheConfig config) {
        this(config, null);
    }

    /**
     * Create negative cache wired with a cross-instance invalidation
     * pubsub channel. When {@code pubsub} is non-null, every
     * invalidation method publishes on the {@value #PUBSUB_CHANNEL}
     * namespace AFTER the local L1 + L2 wipe, and the constructor
     * subscribes to the same namespace so peer invalidations drop
     * this instance's L1 entries.
     *
     * @param config Unified negative cache configuration.
     * @param pubsub Cross-instance pubsub (may be {@code null} for
     *               single-node deployments and tests).
     */
    public NegativeCache(final NegativeCacheConfig config, final CacheInvalidationPubSub pubsub) {
        this.enabled = true;
        this.ttl = config.l2Ttl();
        final RedisAsyncCommands<String, byte[]> l2Commands =
            GlobalCacheConfig.valkeyConnection()
                .filter(v -> config.isValkeyEnabled())
                .map(ValkeyConnection::async)
                .orElse(null);
        this.l2 = l2Commands;
        this.twoTier = l2Commands != null;
        final int maxSize = config.isValkeyEnabled() ? config.l1MaxSize() : config.maxSize();
        final Duration l1Ttl = config.isValkeyEnabled() ? config.l1Ttl() : config.ttl();
        this.notFoundCache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        this.pubsub = pubsub;
        if (pubsub != null) {
            pubsub.subscribe(PUBSUB_CHANNEL, this::onPeerInvalidate);
        }
    }

    /**
     * Handler for peer-invalidation messages. Drops L1 (and L2 if
     * two-tier, though the publishing peer already cleared L2). Never
     * re-publishes — would cause an infinite ping-pong otherwise.
     */
    private void onPeerInvalidate(final String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if ("*".equals(key)) {
            this.notFoundCache.invalidateAll();
            return;
        }
        if (key.startsWith(NAME_PREFIX)) {
            final String name = key.substring(NAME_PREFIX.length());
            this.invalidateByArtifactNameLocal(name);
            return;
        }
        this.notFoundCache.invalidate(key);
    }

    /** Local-only artifact-name match invalidation; does not publish. */
    private int invalidateByArtifactNameLocal(final String artifactName) {
        if (!this.enabled || artifactName == null || artifactName.isEmpty()) {
            return 0;
        }
        final java.util.List<String> matched = new java.util.ArrayList<>();
        for (final String flat : this.notFoundCache.asMap().keySet()) {
            final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
            if (nck != null && cachedNameMatchesUploaded(nck.artifactName(), artifactName)) {
                matched.add(flat);
            }
        }
        for (final String flat : matched) {
            this.notFoundCache.invalidate(flat);
        }
        return matched.size();
    }

    private static boolean cachedNameMatchesUploaded(
        final String cached, final String uploaded
    ) {
        return cached.equals(uploaded) || uploaded.startsWith(cached + "/");
    }

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
     * Cache a composite key as not found in L1 + L2.
     *
     * <p><b>Caller contract:</b> only invoke this when absence has been
     * <em>positively confirmed</em> — either by an explicit upstream
     * {@code 404 Not Found} response, or by structural knowledge that the
     * artifact cannot exist in this scope (e.g. a group with no proxy
     * members, a cooldown-blocked artifact). <b>Never</b> call this on
     * other client errors (401/403/410/429) or on server errors (5xx,
     * timeouts, exceptions): those are transient or non-absence signals
     * and would poison the cache for the negative TTL window
     * (default 24h).</p>
     *
     * @param key Composite key to cache
     */
    public void cacheNotFound(final NegativeCacheKey key) {
        if (!this.enabled) {
            return;
        }
        // Fix 1: never hard-negative-cache a metadata / version-less request.
        // An empty artifactVersion means the request targets a package's
        // dynamic listing (npm packument, maven-metadata.xml, PyPI simple
        // index, Go @latest) or an unparseable path — NOT an immutable
        // versioned artifact. A packument's "absence" is transient (a version
        // can appear upstream at any moment, and a laundered upstream 4xx can
        // masquerade as a 404), so caching it for the negative TTL produces
        // long-lived false 404s on packages that exist (the npm_group/<pkg>
        // regression). Immutable coordinates (non-empty version) are still
        // cached — if 4.17.21 is genuinely absent it will stay absent.
        if (key.artifactVersion().isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Skipping negative-cache write for version-less "
                    + "(metadata) request")
                .eventCategory("database")
                .eventAction("neg_cache_skip_metadata")
                .field("repository.name", key.scope())
                .field("repository.type", key.repoType())
                .field("package.name", key.artifactName())
                .field("log.source", "application")
                .log();
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
        if (this.pubsub != null) {
            this.pubsub.publish(PUBSUB_CHANNEL, flat);
        }
    }

    /**
     * Invalidate every cached entry whose {@code artifactName} matches the
     * just-uploaded artifact. Called by upload slices after a successful
     * {@code storage.save} so a request that 404'd before the upload (and
     * cached the result) doesn't keep returning 404 once the artifact
     * actually exists.
     *
     * <p>Match semantics:
     * <ul>
     *   <li><b>Exact</b>: cached name == uploaded name → always invalidate.</li>
     *   <li><b>Parent prefix</b>: cached name is a parent of uploaded name
     *       (e.g. cached {@code example.com}, uploaded {@code example.com/hello})
     *       → invalidate. Go's tooling probes parent paths during module
     *       resolution; once a child module exists, those probe-404s should
     *       be re-evaluated.</li>
     * </ul>
     *
     * @param artifactName Canonical artifact identifier just stored
     * @return number of L1 entries invalidated (L2 may have additional)
     */
    public int invalidateByArtifactName(final String artifactName) {
        if (!this.enabled || artifactName == null || artifactName.isEmpty()) {
            return 0;
        }
        final java.util.List<String> matched = new java.util.ArrayList<>();
        for (final String flat : this.notFoundCache.asMap().keySet()) {
            final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
            if (nck != null && cachedNameMatchesUploaded(nck.artifactName(), artifactName)) {
                matched.add(flat);
            }
        }
        for (final String flat : matched) {
            this.notFoundCache.invalidate(flat);
        }
        if (this.twoTier && !matched.isEmpty()) {
            final String[] redisKeys = matched.stream()
                .map(f -> "negative:" + f)
                .toArray(String[]::new);
            this.l2.del(redisKeys);
        }
        // Peer fan-out — every other Pantera instance runs the same
        // name-match scan over its local L1. The publish carries the
        // artifact name rather than every matched flat-key so the
        // payload stays bounded regardless of how many local matches a
        // given peer might have.
        if (this.pubsub != null) {
            this.pubsub.publish(PUBSUB_CHANNEL, NAME_PREFIX + artifactName);
        }
        return matched.size();
    }

    /**
     * Invalidate every cached entry whose {@code artifactName} matches ANY of
     * the supplied names, in a SINGLE pass over L1. This is the batched
     * counterpart to {@link #invalidateByArtifactName(String)}: the async
     * {@code DbConsumer} commits up to a few dozen distinct artifact names per
     * batch, and a separate full L1 scan per name would be O(names × L1) — with
     * L1 sized up to 200k in production that can exceed the consumer's flush
     * cadence. One scan matching all names keeps it O(L1) regardless of batch
     * size. Cross-instance invalidation still publishes one message per name so
     * the existing peer wire protocol ({@link #NAME_PREFIX}) is unchanged.
     *
     * @param names Canonical artifact identifiers just stored (deduplicated by
     *              the caller); {@code null}/empty is a no-op
     * @return number of L1 entries invalidated (L2 may have additional)
     */
    public int invalidateByArtifactNames(final java.util.Collection<String> names) {
        if (!this.enabled || names == null || names.isEmpty()) {
            return 0;
        }
        final java.util.Set<String> wanted = new java.util.HashSet<>(names);
        wanted.remove(null);
        wanted.remove("");
        if (wanted.isEmpty()) {
            return 0;
        }
        final java.util.List<String> matched = new java.util.ArrayList<>();
        for (final String flat : this.notFoundCache.asMap().keySet()) {
            final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
            if (nck == null) {
                continue;
            }
            for (final String name : wanted) {
                if (cachedNameMatchesUploaded(nck.artifactName(), name)) {
                    matched.add(flat);
                    break;
                }
            }
        }
        for (final String flat : matched) {
            this.notFoundCache.invalidate(flat);
        }
        if (this.twoTier && !matched.isEmpty()) {
            final String[] redisKeys = matched.stream()
                .map(f -> "negative:" + f)
                .toArray(String[]::new);
            this.l2.del(redisKeys);
        }
        if (this.pubsub != null) {
            for (final String name : wanted) {
                this.pubsub.publish(PUBSUB_CHANNEL, NAME_PREFIX + name);
            }
        }
        return matched.size();
    }

    /**
     * Synchronously invalidate a batch of composite keys from L1 + L2.
     *
     * @param keys List of composite keys to invalidate
     * @return future completing when invalidation is done
     */
    public CompletableFuture<Void> invalidateBatch(final List<NegativeCacheKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        for (final NegativeCacheKey key : keys) {
            this.notFoundCache.invalidate(key.flat());
        }
        if (this.pubsub != null) {
            for (final NegativeCacheKey key : keys) {
                this.pubsub.publish(PUBSUB_CHANNEL, key.flat());
            }
        }
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

    /**
     * Clear entire cache (local L1 + L2 + peers).
     */
    public void clear() {
        this.notFoundCache.invalidateAll();
        if (this.twoTier) {
            scanAndDelete("negative:*");
        }
        if (this.pubsub != null) {
            this.pubsub.publishAll(PUBSUB_CHANNEL);
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
     * L2 sentinel value — a single ASCII '1'. The value is presence-only;
     * we use {@code '1'} (0x31) instead of byte {@code 0x01} so that
     * {@code valkey-cli GET} renders it as the readable string {@code "1"}
     * rather than the unreadable escape {@code \x01}.
     */
    private static final byte[] L2_SENTINEL = {'1'};

    /**
     * L2 SET with TTL.
     */
    private void l2Set(final String redisKey) {
        this.l2.setex(redisKey, this.ttl.getSeconds(), L2_SENTINEL);
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
