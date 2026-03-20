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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.index.qual.NonNegative;

/**
 * High-performance cache for cooldown block decisions.
 * Three-tier architecture:
 * - L1 (in-memory): Hot data, sub-millisecond lookups
 * - L2 (Valkey/Redis): Warm data, shared across instances
 * - L3 (Database): Source of truth, persistent storage
 *
 * Cache Key Format: cooldown:{repo_name}:{artifact}:{version}:block
 * Cache Value: true (blocked) | false (allowed)
 *
 * @since 1.0
 */
public final class CooldownCache {

    /**
     * L1 cache for block decisions (in-memory, hot data).
     * Key: cooldown:{repo_name}:{artifact}:{version}:block
     * Value: true (blocked) | false (allowed)
     */
    private final Cache<String, Boolean> decisions;

    /**
     * L2 cache (Valkey/Redis, warm data) - optional.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;

    /**
     * TTL for allowed (false) entries in L1 cache.
     */
    private final Duration l1AllowedTtl;

    /**
     * TTL for allowed (false) entries in L2 cache (seconds).
     */
    private final long l2AllowedTtlSeconds;

    /**
     * In-flight requests to prevent duplicate concurrent evaluations.
     * Key: cooldown:{repoName}:{artifact}:{version}:block
     * Value: CompletableFuture<Boolean>
     */
    private final ConcurrentMap<String, CompletableFuture<Boolean>> inflight;

    /**
     * Cache statistics.
     */
    private volatile long hits;
    private volatile long misses;
    private volatile long deduplications;

    /**
     * Constructor with default settings.
     * Auto-connects to Valkey if GlobalCacheConfig is initialized.
     * - Decision cache: 10,000 entries
     * - Default TTL: 24 hours (single-tier) or 5min/1h (two-tier)
     */
    public CooldownCache() {
        this(
            10_000,
            Duration.ofHours(24),  // Default single-tier TTL
            com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null)
        );
    }

    /**
     * Constructor with Valkey connection (two-tier) and default TTLs.
     * - L1 (in-memory): 10,000 entries, 5min TTL for allowed
     * - L2 (Valkey): unlimited entries, 1h TTL for allowed
     *
     * @param valkey Valkey connection for L2 cache
     */
    public CooldownCache(final ValkeyConnection valkey) {
        this(10_000, Duration.ofMinutes(5), Duration.ofHours(1), valkey);
    }

    /**
     * Constructor with custom settings (single-tier).
     *
     * @param decisionMaxSize Maximum decision cache size
     * @param allowedTtl TTL for allowed (false) cache entries
     * @param valkey Valkey connection for L2 cache (null for single-tier)
     */
    public CooldownCache(
        final long decisionMaxSize,
        final Duration allowedTtl,
        final ValkeyConnection valkey
    ) {
        this(
            decisionMaxSize,
            allowedTtl,
            valkey != null ? Duration.ofHours(1) : allowedTtl,  // L2 defaults to 1h or same as L1
            valkey
        );
    }

    /**
     * Constructor with custom settings (two-tier).
     *
     * @param decisionMaxSize Maximum decision cache size (L1)
     * @param l1AllowedTtl TTL for allowed (false) entries in L1
     * @param l2AllowedTtl TTL for allowed (false) entries in L2
     * @param valkey Valkey connection for L2 cache (null for single-tier)
     */
    public CooldownCache(
        final long decisionMaxSize,
        final Duration l1AllowedTtl,
        final Duration l2AllowedTtl,
        final ValkeyConnection valkey
    ) {
        this.twoTier = (valkey != null);
        this.l2 = this.twoTier ? valkey.async() : null;
        this.l1AllowedTtl = l1AllowedTtl;
        this.l2AllowedTtlSeconds = l2AllowedTtl.getSeconds();
        
        // L1 Boolean cache: Simple true/false for block decisions
        // TTL: Configurable for allowed entries
        this.decisions = Caffeine.newBuilder()
            .maximumSize(decisionMaxSize)
            .expireAfterWrite(l1AllowedTtl)
            .recordStats()
            .build();
        
        this.inflight = new ConcurrentHashMap<>();
        this.hits = 0;
        this.misses = 0;
        this.deduplications = 0;
    }

    /**
     * Check if artifact is blocked (3-tier lookup).
     * Returns true if blocked, false if allowed.
     *
     * @param repoName Repository name
     * @param artifact Artifact name
     * @param version Version
     * @param dbQuery Database query function (only called on L1+L2 miss)
     * @return CompletableFuture with true (blocked) or false (allowed)
     */
    public CompletableFuture<Boolean> isBlocked(
        final String repoName,
        final String artifact,
        final String version,
        final java.util.function.Supplier<CompletableFuture<Boolean>> dbQuery
    ) {
        final String key = blockKey(repoName, artifact, version);
        final long l1StartNanos = System.nanoTime();
        
        // L1: Fast path - check in-memory cache
        final Boolean l1Cached = this.decisions.getIfPresent(key);
        if (l1Cached != null) {
            this.hits++;
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("cooldown", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("cooldown", "l1", "get", durationMs);
            }
            return CompletableFuture.completedFuture(l1Cached);
        }

        // L1 MISS
        this.misses++;
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            final long durationMs = (System.nanoTime() - l1StartNanos) / 1_000_000;
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("cooldown", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("cooldown", "l1", "get", durationMs);
        }
        
        // Two-tier: Check L2 (Valkey) before database
        if (this.twoTier) {
            final long l2StartNanos = System.nanoTime();
            
            return this.l2.get(key)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> {
                    // Track L2 error - metrics handled elsewhere
                    return null;  // L2 failure → skip to database
                })
                .thenCompose(l2Bytes -> {
                    final long durationMs = (System.nanoTime() - l2StartNanos) / 1_000_000;

                    if (l2Bytes != null) {
                        // L2 HIT
                        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("cooldown", "l2");
                            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("cooldown", "l2", "get", durationMs);
                        }

                        // Parse boolean and promote to L1
                        final boolean blocked = "true".equals(new String(l2Bytes));
                        this.decisions.put(key, blocked);  // Promote to L1
                        return CompletableFuture.completedFuture(blocked);
                    }

                    // L2 MISS
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("cooldown", "l2");
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheOperationDuration("cooldown", "l2", "get", durationMs);
                    }

                    // Query database
                    return this.queryAndCache(key, dbQuery);
                });
        }
        
        // Single-tier: Query database
        return this.queryAndCache(key, dbQuery);
    }

    /**
     * Query database and cache result.
     * Only caches ALLOWED (false) entries to L2 with configured TTL.
     * BLOCKED (true) entries must be cached by caller using putBlocked() with dynamic TTL.
     */
    private CompletableFuture<Boolean> queryAndCache(
        final String key,
        final java.util.function.Supplier<CompletableFuture<Boolean>> dbQuery
    ) {
        // Deduplication: check if already querying
        final CompletableFuture<Boolean> existing = this.inflight.get(key);
        if (existing != null) {
            this.deduplications++;
            // Deduplication metrics can be added if needed
            return existing;
        }
        
        // Query database
        final CompletableFuture<Boolean> future = dbQuery.get()
            .whenComplete((blocked, error) -> {
                this.inflight.remove(key);
                if (error == null && blocked != null) {
                    // Cache in L1
                    this.decisions.put(key, blocked);
                    // Cache in L2 only for ALLOWED entries (false)
                    // BLOCKED entries (true) are cached by service layer with dynamic TTL
                    if (this.twoTier && !blocked) {
                        this.putL2Boolean(key, false, this.l2AllowedTtlSeconds);
                    }
                }
            });
        
        // Register inflight to deduplicate concurrent requests
        this.inflight.put(key, future);
        
        return future;
    }

    /**
     * Cache a block decision with configured TTL for allowed entries.
     *
     * @param repoName Repository name
     * @param artifact Artifact name
     * @param version Version
     * @param blocked True if blocked, false if allowed
     */
    public void put(
        final String repoName,
        final String artifact,
        final String version,
        final boolean blocked
    ) {
        final String key = blockKey(repoName, artifact, version);
        // Store in L1
        this.decisions.put(key, blocked);
        
        // Store in L2 if two-tier (uses configured TTL)
        if (this.twoTier) {
            this.putL2Boolean(key, blocked, this.l2AllowedTtlSeconds);
        }
    }

    /**
     * Cache a blocked decision with dynamic TTL (until block expires).
     * Only call this for blocked=true entries.
     *
     * @param repoName Repository name
     * @param artifact Artifact name
     * @param version Version
     * @param blockedUntil When the block expires
     */
    public void putBlocked(
        final String repoName,
        final String artifact,
        final String version,
        final Instant blockedUntil
    ) {
        final String key = blockKey(repoName, artifact, version);
        // Store in L1
        this.decisions.put(key, true);
        
        // Store in L2 with dynamic TTL (until block expires)
        if (this.twoTier) {
            final long ttlSeconds = Duration.between(Instant.now(), blockedUntil).getSeconds();
            if (ttlSeconds > 0) {
                this.putL2Boolean(key, true, ttlSeconds);
            }
        }
    }

    /**
     * Unblock specific artifact (set cache to false).
     * Called when artifact is manually unblocked.
     *
     * @param repoName Repository name
     * @param artifact Artifact name
     * @param version Version
     */
    public void unblock(
        final String repoName,
        final String artifact,
        final String version
    ) {
        final String key = blockKey(repoName, artifact, version);
        
        // Set L1 to false (allowed)
        this.decisions.put(key, false);
        
        // Set L2 to false with configured TTL
        if (this.twoTier) {
            this.l2.setex(key, this.l2AllowedTtlSeconds, "false".getBytes());
        }
    }

    /**
     * Unblock all artifacts in repository (set all to false).
     * Called when all artifacts are manually unblocked.
     *
     * @param repoName Repository name
     */
    public void unblockAll(final String repoName) {
        final String prefix = "cooldown:" + repoName + ":";
        
        // L1: Set all matching keys to false
        this.decisions.asMap().keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .forEach(key -> this.decisions.put(key, false));
        
        // L2: Pattern update (SCAN is expensive but unblockAll is rare)
        if (this.twoTier) {
            final String pattern = prefix + "*";
            this.scanAndUpdate(pattern);
        }
    }

    /**
     * Get cache statistics.
     *
     * @return Statistics string
     */
    public String stats() {
        final long total = this.hits + this.misses;
        if (total == 0) {
            return this.twoTier 
                ? "CooldownCache[two-tier, empty]"
                : "CooldownCache[single-tier, empty]";
        }
        
        final double hitRate = 100.0 * this.hits / total;
        final long dedup = this.deduplications;
        final long decisions = this.decisions.estimatedSize();
        
        if (this.twoTier) {
            return String.format(
                "CooldownCache[two-tier, L1: decisions=%d, hits=%d, misses=%d, hitRate=%.1f%%, dedup=%d]",
                decisions, this.hits, this.misses, hitRate, dedup
            );
        }
        
        return String.format(
            "CooldownCache[single-tier, decisions=%d, hits=%d, misses=%d, hitRate=%.1f%%, dedup=%d]",
            decisions, this.hits, this.misses, hitRate, dedup
        );
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        this.decisions.invalidateAll();
        this.inflight.clear();
        this.hits = 0;
        this.misses = 0;
        this.deduplications = 0;
    }

    /**
     * Generate cache key for block decision.
     * Format: cooldown:{repo_name}:{artifact}:{version}:block
     */
    private String blockKey(
        final String repoName,
        final String artifact,
        final String version
    ) {
        return String.format(
            "cooldown:%s:%s:%s:block",
            repoName,
            artifact,
            version
        );
    }

    /**
     * Put boolean value in L2 cache with custom TTL.
     */
    private void putL2Boolean(final String key, final boolean blocked, final long ttlSeconds) {
        final byte[] value = (blocked ? "true" : "false").getBytes();
        this.l2.setex(key, ttlSeconds, value);
    }

    /**
     * Scan and update keys matching pattern using cursor-based SCAN.
     * Sets each matched key to "false" (allowed) with configured TTL.
     * Avoids blocking KEYS command that freezes Redis on large datasets.
     * @param pattern Redis key pattern (glob-style)
     */
    private CompletableFuture<Void> scanAndUpdate(final String pattern) {
        return this.scanAndUpdateStep(ScanCursor.INITIAL, pattern);
    }

    private CompletableFuture<Void> scanAndUpdateStep(
        final ScanCursor cursor, final String pattern
    ) {
        return this.l2.scan(cursor, ScanArgs.Builder.matches(pattern).limit(100))
            .toCompletableFuture()
            .thenCompose(result -> {
                for (final String key : result.getKeys()) {
                    this.l2.setex(key, this.l2AllowedTtlSeconds, "false".getBytes());
                }
                if (result.isFinished()) {
                    return CompletableFuture.completedFuture(null);
                }
                return this.scanAndUpdateStep(result, pattern);
            });
    }

}
