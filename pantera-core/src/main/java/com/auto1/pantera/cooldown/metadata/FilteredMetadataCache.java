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

import com.auto1.pantera.asto.misc.Cleanable;
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
 * <p>Cache key format:
 * {@code metadata:{repoType}:{repoName}:{variant}:{packageName}} — the
 * variant names the body shape (npm {@code full} vs {@code abbreviated};
 * {@code default} for single-shape callers), and the package name is
 * always the last segment (both by-package invalidation paths match on
 * that suffix). L2 values above 1 KB are stored gzip-compressed; the
 * decoder falls back to raw for legacy entries.</p>
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
public class FilteredMetadataCache implements Cleanable<String> {

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
     * Variant segment for callers that do not distinguish body shapes of
     * the same package's metadata (see {@link #cacheKey}).
     */
    static final String DEFAULT_VARIANT = "default";

    /**
     * L2 read timeout. Was 100 ms, which a multi-megabyte envelope (a full
     * npm packument runs to tens of MB) could never satisfy — every serve
     * then paid the full timeout, discarded the in-flight transfer, redid
     * the filter AND re-wrote the value to L2: strictly worse than either
     * a completed hit or a plain recompute. 500 ms is a ceiling, not a
     * wait — small values still return in ~1 ms; gzip (see
     * {@link #l2Encode}) keeps even the largest envelopes comfortably
     * inside it. The {@link #l2ReadAllowed()} breaker bounds the damage
     * when Valkey is genuinely degraded.
     */
    private static final long L2_READ_TIMEOUT_MS = 500;

    /**
     * Consecutive L2 read failures (timeout or error) after which L2 reads
     * are skipped for {@link #L2_SKIP_WINDOW} — a degraded Valkey must not
     * add the read timeout to every metadata serve across all packages.
     */
    private static final int L2_STRIKES_TO_SKIP = 3;

    /**
     * How long L2 reads stay skipped after the strike threshold trips.
     * Writes stay enabled (they are async fire-and-forget).
     */
    private static final Duration L2_SKIP_WINDOW = Duration.ofSeconds(10);

    /**
     * Threshold below which L2 values are stored raw — gzip overhead is not
     * worth it for tiny envelopes, and the decoder handles both forms.
     */
    private static final int L2_COMPRESS_MIN_BYTES = 1024;

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
     * Optional per-key invalidation publisher. Wired by
     * {@code CooldownSupport} to broadcast dropped envelope keys on the
     * {@code cooldown-envelope} pub/sub channel so peer instances drop
     * their L1 entries too. The receive side ({@link #invalidate(String)})
     * never re-publishes — that is the no-loop guarantee. {@code null}
     * (default) = single-instance deployments and tests: no fan-out.
     */
    private volatile java.util.function.Consumer<String> invalidationPublisher;

    /**
     * Consecutive L2 read failures; reset on any successful L2 answer
     * (hit or miss). At {@link #L2_STRIKES_TO_SKIP} the read path is
     * skipped until {@link #l2SkipUntilNanos}.
     */
    private final java.util.concurrent.atomic.AtomicInteger l2Strikes =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Monotonic deadline (nanos) until which L2 reads are skipped; 0 = not
     * skipping.
     */
    private volatile long l2SkipUntilNanos;

    /**
     * Monotonic clock for the L2 read breaker; package-private setter for
     * time-travel in tests.
     */
    private java.util.function.LongSupplier nanoClock = System::nanoTime;

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
        return this.getEntry(repoType, repoName, packageName, loader)
            .thenApply(CacheEntry::data);
    }

    /**
     * Same lookup as {@link #get} but resolving the whole {@link CacheEntry},
     * so the caller can read {@link CacheEntry#blockedVersions()} and emit an
     * accurate {@code artifact_resolution} audit record for cache-hit serves
     * — the bytes alone cannot tell "nothing was filtered" apart from
     * "versions were filtered when this entry was computed".
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     * @param loader Function to compute filtered metadata on cache miss
     * @return CompletableFuture with the cache entry (L1, promoted-L2, or computed)
     */
    public CompletableFuture<CacheEntry> getEntry(
        final String repoType,
        final String repoName,
        final String packageName,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        return this.getEntry(
            repoType, repoName, DEFAULT_VARIANT, packageName, loader
        );
    }

    /**
     * Variant-aware {@link #getEntry(String, String, String, java.util.function.Supplier)}.
     *
     * <p>The {@code variant} names the body shape the loader filters —
     * e.g. the npm proxy caches the full packument and the abbreviated
     * (install-v1) packument under {@code "full"} / {@code "abbreviated"}.
     * Before the variant segment existed, both shapes shared one envelope
     * key and whichever computed first was served to both kinds of
     * request — an install-v1 client could receive the full packument
     * (and vice versa) for the envelope's whole TTL.</p>
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param variant Body-shape discriminator (see {@link #cacheKey})
     * @param packageName Package name
     * @param loader Function to compute filtered metadata on cache miss
     * @return CompletableFuture with the cache entry (L1, promoted-L2, or computed)
     */
    public CompletableFuture<CacheEntry> getEntry(
        final String repoType,
        final String repoName,
        final String variant,
        final String packageName,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        final String key = cacheKey(repoType, repoName, variant, packageName);

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
                    return CompletableFuture.completedFuture(l1Cached);
                }
                this.l1Hits++;
                if (CooldownMetrics.isAvailable()) {
                    CooldownMetrics.getInstance().recordCacheHit("l1");
                }
                return CompletableFuture.completedFuture(l1Cached);
            }
        }

        // L2 check — skipped while the read breaker is open (a degraded
        // Valkey must not add the read timeout to every serve). Writes
        // stay enabled; they are async fire-and-forget.
        if (this.l2Connection != null && this.l2ReadAllowed()) {
            return this.l2Connection.async().get(key)
                .toCompletableFuture()
                .orTimeout(L2_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((raw, err) -> {
                    if (err != null) {
                        this.recordL2ReadFailure(err);
                        return null;
                    }
                    // Any answered read — hit or miss — proves L2 healthy.
                    this.l2Strikes.set(0);
                    if (raw == null) {
                        return null;
                    }
                    final byte[] decoded = l2Decode(raw);
                    return decoded.length == 0 ? null : decoded;
                })
                .thenCompose(l2Bytes -> {
                    if (l2Bytes != null) {
                        this.l2Hits++;
                        if (CooldownMetrics.isAvailable()) {
                            CooldownMetrics.getInstance().recordCacheHit("l2");
                        }
                        // L2 stores raw bytes only — TTL and blocked-version
                        // detail do not survive the round trip, so the
                        // promoted entry carries null blockedVersions
                        // ("unknown") and L1 TTL.
                        final CacheEntry entry = new CacheEntry(l2Bytes, Optional.empty(), this.l1Ttl);
                        if (!this.l2OnlyMode && this.l1Cache != null) {
                            this.l1Cache.put(key, entry);
                        }
                        return CompletableFuture.completedFuture(entry);
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
    private CompletableFuture<CacheEntry> loadAndCache(
        final String key,
        final java.util.function.Supplier<CompletableFuture<CacheEntry>> loader
    ) {
        // Check if already loading (stampede prevention)
        final CompletableFuture<CacheEntry> existing = this.inflight.get(key);
        if (existing != null) {
            return existing;
        }

        // Start loading -- register in inflight BEFORE whenComplete
        final CompletableFuture<CacheEntry> future = loader.get();
        this.inflight.put(key, future);
        future.whenComplete((entry, error) -> {
            this.inflight.remove(key);
            if (error == null && entry != null) {
                // Cache in L1 with L1 TTL (skip in L2-only mode)
                if (!this.l2OnlyMode && this.l1Cache != null) {
                    // Wrap entry with L1 TTL for proper expiration, preserving
                    // the blocked-version detail for cache-hit audit records.
                    final CacheEntry l1Entry = new CacheEntry(
                        entry.data(),
                        entry.earliestBlockedUntil(),
                        this.l1Ttl,
                        entry.blockedVersions()
                    );
                    this.l1Cache.put(key, l1Entry);
                }
                // Cache in L2 with L2 TTL (use configured l2Ttl, capped by
                // blockedUntil if present). Values above the threshold are
                // gzip-compressed — packument JSON shrinks 5–10x, which is
                // what keeps multi-MB envelopes inside the read timeout.
                if (this.l2Connection != null) {
                    final long ttlSeconds = this.calculateL2Ttl(entry);
                    if (ttlSeconds > 0) {
                        this.l2Connection.async().setex(key, ttlSeconds, l2Encode(entry.data()));
                    }
                }
            }
        });

        return future;
    }

    /**
     * Invalidate cached metadata for a package — every variant of it.
     * Called when a version is blocked or unblocked.
     *
     * <p>Since the variant segment was added to the key, one package can
     * hold several envelopes (e.g. npm {@code full} + {@code abbreviated});
     * a block-state change affects all of them, so this matches on the
     * {@code metadata:{repoType}:{repoName}:} prefix plus the
     * {@code :{packageName}} suffix in L1, sweeps L2 with the anchored glob
     * {@code metadata:{repoType}:{repoName}:*:{packageName}}, and publishes
     * every dropped key to peers. (Pre-variant L2 entries — three segments,
     * no variant — match nothing here; nothing reads their key shape any
     * more, so they simply age out via TTL.)</p>
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
        final String prefix = "metadata:" + repoType + ":" + repoName + ":";
        final String suffix = ":" + packageName;
        if (this.l1Cache != null) {
            for (final String key : this.l1Cache.asMap().keySet()) {
                if (key.startsWith(prefix) && key.endsWith(suffix)) {
                    this.l1Cache.invalidate(key);
                    this.inflight.remove(key);
                    this.publishInvalidation(key);
                }
            }
        }
        this.inflight.keySet().stream()
            .filter(key -> key.startsWith(prefix) && key.endsWith(suffix))
            .forEach(this.inflight::remove);
        if (this.l2Connection != null) {
            final String pattern = "metadata:" + escapeGlob(repoType) + ":"
                + escapeGlob(repoName) + ":*:" + escapeGlob(packageName);
            this.sweepL2Step(
                io.lettuce.core.ScanCursor.INITIAL, pattern, packageName, 0
            );
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
     * Invalidate every cached envelope whose package name matches
     * {@code packageName}, across every {@code (repoType, repoName)}
     * combination currently in cache.
     *
     * <p>Called by adapter upload paths after a successful publish so
     * any envelope cached for a group that contains the uploaded-to
     * local repo (or any other place this package's metadata is
     * filtered) is dropped — without this, the next metadata request
     * keeps serving the pre-upload version list for up to the static
     * cache TTL (default 30 days when no blocks are active).
     *
     * <p>Match shape: the cache key is
     * {@code metadata:{repoType}:{repoName}:{packageName}}, so L1 keys are
     * matched with {@code key.endsWith(":" + packageName)} and L2 keys with
     * the anchored glob {@code metadata:*:{packageName}} — exact suffix
     * match on the package-name segment either way. Package names that
     * don't contain colons (the universal case in Pantera-supported
     * registries) round-trip cleanly.
     *
     * <p><b>The L2 sweep is independent of L1.</b> Before 2.2.7 the Valkey
     * {@code DEL} was issued only for keys found in the local L1 scan — but
     * by the time a background refresh fires this invalidation, the short-
     * lived L1 twin of the envelope is typically already evicted (and in
     * L2-only mode never existed), so the stale envelope survived in Valkey
     * for the full L2 TTL and was re-promoted into L1 on every serve. That
     * was the 2.2.6 "npm metadata never refreshes" incident. The sweep now
     * SCANs L2 by pattern regardless of L1 state, deletes every match, and
     * publishes each dropped key to peers via the configured
     * {@link #setInvalidationPublisher(java.util.function.Consumer)}.
     *
     * @param packageName Canonical package name as the cache stores it.
     * @return number of L1 entries invalidated on this instance; the L2
     *  sweep completes asynchronously and logs its own outcome.
     */
    public int invalidateByPackageName(final String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return 0;
        }
        final String suffix = ":" + packageName;
        final java.util.List<String> matched = new java.util.ArrayList<>();
        if (this.l1Cache != null) {
            for (final String key : this.l1Cache.asMap().keySet()) {
                if (key.endsWith(suffix)) {
                    matched.add(key);
                }
            }
            for (final String key : matched) {
                this.l1Cache.invalidate(key);
                this.inflight.remove(key);
                this.publishInvalidation(key);
            }
        }
        if (this.l2Connection != null) {
            this.sweepL2(packageName);
        }
        return matched.size();
    }

    /**
     * Register the cross-instance invalidation publisher — called once at
     * boot by {@code CooldownSupport} when pub/sub is wired.
     *
     * @param publisher Consumer receiving each dropped canonical cache key;
     *  {@code null} disables fan-out (tests)
     */
    public void setInvalidationPublisher(final java.util.function.Consumer<String> publisher) {
        this.invalidationPublisher = publisher;
    }

    /**
     * Publish one dropped key to peers; never throws into the caller.
     *
     * @param key Canonical cache key that was invalidated locally
     */
    private void publishInvalidation(final String key) {
        final java.util.function.Consumer<String> publisher = this.invalidationPublisher;
        if (publisher != null) {
            try {
                publisher.accept(key);
            } catch (final RuntimeException ex) {
                com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cooldown.metadata")
                    .message("Envelope invalidation pub/sub publish failed; peers rely on TTL")
                    .eventCategory("database")
                    .eventAction("envelope_invalidate_publish")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    /**
     * Asynchronously delete every L2 envelope whose package-name segment
     * matches, via cursor-based SCAN (never the blocking KEYS command).
     * Each deleted key is also dropped from L1/in-flight and published to
     * peers. The final outcome is logged so a refresh-driven invalidation
     * is visible in the log stream even when this instance's L1 held
     * nothing.
     *
     * @param packageName Canonical package name (last key segment)
     */
    private void sweepL2(final String packageName) {
        final String pattern = "metadata:*:" + escapeGlob(packageName);
        this.sweepL2Step(io.lettuce.core.ScanCursor.INITIAL, pattern, packageName, 0);
    }

    /**
     * One SCAN page of {@link #sweepL2(String)}; recurses until the cursor
     * finishes.
     *
     * @param cursor Scan cursor position
     * @param pattern Anchored glob pattern for the envelope keys
     * @param packageName Package name, for logging
     * @param deleted Keys deleted by previous pages
     */
    private void sweepL2Step(
        final io.lettuce.core.ScanCursor cursor,
        final String pattern,
        final String packageName,
        final int deleted
    ) {
        this.l2Connection.async()
            .scan(cursor, io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(500))
            .whenComplete((result, error) -> {
                if (error != null) {
                    com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cooldown.metadata")
                        .message("L2 envelope sweep failed; stale entries expire via TTL")
                        .eventCategory("database")
                        .eventAction("envelope_invalidate_l2")
                        .eventOutcome("failure")
                        .field("package.name", packageName)
                        .error(error)
                        .field("log.source", "application")
                        .log();
                    return;
                }
                final java.util.List<String> keys = result.getKeys();
                if (!keys.isEmpty()) {
                    this.l2Connection.async().del(keys.toArray(new String[0]));
                    for (final String key : keys) {
                        if (this.l1Cache != null) {
                            this.l1Cache.invalidate(key);
                        }
                        this.inflight.remove(key);
                        this.publishInvalidation(key);
                    }
                }
                final int total = deleted + keys.size();
                if (result.isFinished()) {
                    if (total > 0) {
                        com.auto1.pantera.http.log.EcsLogger.info("com.auto1.pantera.cooldown.metadata")
                            .message("L2 envelope sweep dropped " + total + " stale entrie(s)")
                            .eventCategory("database")
                            .eventAction("envelope_invalidate_l2")
                            .eventOutcome("success")
                            .field("package.name", packageName)
                            .field("log.source", "application")
                            .log();
                    }
                } else {
                    this.sweepL2Step(result, pattern, packageName, total);
                }
            });
    }

    /**
     * Whether L2 reads are currently allowed (the read breaker is closed).
     *
     * @return {@code true} when the L2 GET may be attempted
     */
    boolean l2ReadAllowed() {
        return this.nanoClock.getAsLong() >= this.l2SkipUntilNanos;
    }

    /**
     * Record one failed L2 read (timeout or transport error). At
     * {@link #L2_STRIKES_TO_SKIP} consecutive failures, L2 reads are
     * skipped for {@link #L2_SKIP_WINDOW} — serves fall straight through
     * to the local recompute instead of stalling on a degraded Valkey.
     *
     * @param err The read failure
     */
    void recordL2ReadFailure(final Throwable err) {
        final int strikes = this.l2Strikes.incrementAndGet();
        if (strikes == L2_STRIKES_TO_SKIP) {
            this.l2SkipUntilNanos = this.nanoClock.getAsLong() + L2_SKIP_WINDOW.toNanos();
            com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cooldown.metadata")
                .message("L2 envelope reads degraded ("
                    + strikes + " consecutive failures) — skipping L2 for "
                    + L2_SKIP_WINDOW.toSeconds() + "s, serving from recompute")
                .eventCategory("database")
                .eventAction("envelope_l2_degraded")
                .eventOutcome("failure")
                .error(err)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Override the breaker clock (tests only).
     *
     * @param clock Monotonic nano clock
     */
    void nanoClock(final java.util.function.LongSupplier clock) {
        this.nanoClock = clock;
    }

    /**
     * Encode a value for L2 storage: gzip above
     * {@link #L2_COMPRESS_MIN_BYTES} (packument JSON shrinks 5–10x, which
     * keeps even tens-of-MB envelopes inside {@link #L2_READ_TIMEOUT_MS}),
     * raw below it. The decoder distinguishes the two by the gzip magic
     * bytes, which also keeps pre-compression entries readable during a
     * rolling upgrade.
     *
     * @param data Envelope bytes
     * @return Bytes to store in L2
     */
    static byte[] l2Encode(final byte[] data) {
        if (data.length < L2_COMPRESS_MIN_BYTES) {
            return data;
        }
        try (java.io.ByteArrayOutputStream bos =
                 new java.io.ByteArrayOutputStream(Math.max(64, data.length / 4))) {
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
                gz.write(data);
            }
            return bos.toByteArray();
        } catch (final java.io.IOException ex) {
            // In-memory gzip cannot realistically fail; fall back to raw
            // rather than losing the write.
            return data;
        }
    }

    /**
     * Decode an L2 value: gunzip when the gzip magic is present, else the
     * bytes are a raw (small or pre-compression) envelope. A corrupt value
     * decodes to an empty array so the caller treats it as a miss and
     * recomputes — a genuine envelope is never empty.
     *
     * @param stored Bytes read from L2
     * @return Envelope bytes; empty when undecodable
     */
    static byte[] l2Decode(final byte[] stored) {
        if (stored.length < 2
            || (stored[0] & 0xFF) != 0x1F || (stored[1] & 0xFF) != 0x8B) {
            return stored;
        }
        try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
            new java.io.ByteArrayInputStream(stored)
        )) {
            return gz.readAllBytes();
        } catch (final java.io.IOException ex) {
            com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cooldown.metadata")
                .message("Undecodable L2 envelope value — treating as cache miss")
                .eventCategory("database")
                .eventAction("envelope_l2_decode")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
            return new byte[0];
        }
    }

    /**
     * Escape Redis glob metacharacters so a literal package name cannot be
     * misread as a pattern.
     *
     * @param raw Package name
     * @return Glob-safe literal
     */
    private static String escapeGlob(final String raw) {
        final StringBuilder out = new StringBuilder(raw.length());
        for (int idx = 0; idx < raw.length(); idx = idx + 1) {
            final char chr = raw.charAt(idx);
            if (chr == '*' || chr == '?' || chr == '[' || chr == ']' || chr == '\\') {
                out.append('\\');
            }
            out.append(chr);
        }
        return out.toString();
    }

    /**
     * Clear all caches (L1 and L2). Used on global policy changes such as
     * cooldown settings updates — without the L2 wipe, peer L1 caches and
     * the local L1 after restart re-hydrate from stale L2 entries that
     * predate the policy change. The L2 pattern delete is expensive but
     * runs at most once per settings update.
     */
    public void clear() {
        if (this.l1Cache != null) {
            this.l1Cache.invalidateAll();
        }
        this.inflight.clear();
        this.l1Hits = 0;
        this.l2Hits = 0;
        this.misses = 0;
        if (this.l2Connection != null) {
            this.l2Connection.async().keys("metadata:*")
                .thenAccept(keys -> {
                    if (keys != null && !keys.isEmpty()) {
                        this.l2Connection.async().del(keys.toArray(new String[0]));
                    }
                });
        }
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
     *
     * <p>Exposed so {@code JdbcCooldownService} can pass the canonical key
     * shape into {@link com.auto1.pantera.cache.CacheInvalidationPubSub}
     * when broadcasting cross-instance invalidations — the subscriber side
     * calls {@link #invalidate(String)} with the same shape so the L1
     * envelope entry is dropped on every peer.</p>
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     * @return Canonical L1/L2 cache key
     */
    public static String cacheKey(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        return cacheKey(repoType, repoName, DEFAULT_VARIANT, packageName);
    }

    /**
     * Canonical variant-aware cache key:
     * {@code metadata:{repoType}:{repoName}:{variant}:{packageName}}.
     *
     * <p>The variant names the body shape being cached (npm: {@code full}
     * vs {@code abbreviated}); {@code default} for callers with a single
     * shape. The package name stays the LAST segment — both by-package
     * invalidation paths ({@link #invalidateByPackageName} and
     * {@link #invalidate(String, String, String)}) match on the
     * {@code :{packageName}} suffix, so any future key change must keep
     * this position.</p>
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param variant Body-shape discriminator
     * @param packageName Package name
     * @return Canonical L1/L2 cache key
     */
    public static String cacheKey(
        final String repoType,
        final String repoName,
        final String variant,
        final String packageName
    ) {
        return String.format(
            "metadata:%s:%s:%s:%s", repoType, repoName, variant, packageName
        );
    }

    /**
     * Drop a single L1 entry by its full canonical key. Receive-side
     * handler for cross-instance pub/sub fan-out: the originator already
     * cleared its own L1+L2; peers only need to drop their local L1.
     *
     * <p><b>Does not publish and does not delete from L2.</b> Re-publishing
     * here would create an invalidation loop; the L2 deletion was already
     * issued by the originator's local mutator path. Double-deleting L2 is
     * harmless but wasteful, so we skip it.</p>
     *
     * @param key Canonical cache key produced by {@link #cacheKey}
     */
    @Override
    public void invalidate(final String key) {
        if (this.l1Cache != null) {
            this.l1Cache.invalidate(key);
        }
        this.inflight.remove(key);
    }

    /**
     * Drop all L1 entries. Receive-side handler for cross-instance pub/sub
     * fan-out of {@code unblockAll}. Does not publish and does not touch
     * L2 — see {@link #invalidate(String)} for the rationale.
     */
    @Override
    public void invalidateAll() {
        if (this.l1Cache != null) {
            this.l1Cache.invalidateAll();
        }
        this.inflight.clear();
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
         * Versions hidden from this listing by cooldown at compute time.
         * Carried L1-only so a cache-hit serve can still emit an accurate
         * {@code artifact_resolution} audit record (who saw the listing and
         * which versions were filtered). {@code null} means "detail unknown"
         * — the entry was promoted from L2 (Valkey stores raw bytes only)
         * and the blocked-version list did not survive the round trip.
         */
        private final java.util.Set<String> blockedVersions;

        /**
         * Constructor (blocked-version detail unknown).
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
            this(data, earliestBlockedUntil, maxTtl, null);
        }

        /**
         * Constructor.
         *
         * @param data Filtered metadata bytes
         * @param earliestBlockedUntil Earliest blockedUntil among blocked versions (empty if none blocked)
         * @param maxTtl Maximum TTL when no versions are blocked
         * @param blockedVersions Versions hidden by cooldown at compute time;
         *                        empty set when nothing was filtered; {@code null}
         *                        when the detail is unknown (L2 promotion)
         */
        public CacheEntry(
            final byte[] data,
            final Optional<Instant> earliestBlockedUntil,
            final Duration maxTtl,
            final java.util.Set<String> blockedVersions
        ) {
            this.data = data; // NOPMD ArrayIsStoredDirectly - immutable cache value; defensive copy of filtered metadata bytes is wasteful
            this.earliestBlockedUntil = earliestBlockedUntil;
            this.maxTtl = maxTtl;
            this.createdAt = Instant.now();
            this.blockedVersions = blockedVersions == null
                ? null : java.util.Set.copyOf(blockedVersions);
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
         * Versions hidden by cooldown when this entry was computed.
         *
         * @return Blocked versions (empty set = nothing filtered), or
         *         {@code null} when the detail is unknown (entry promoted
         *         from L2, which stores raw bytes only)
         */
        public java.util.Set<String> blockedVersions() {
            return this.blockedVersions;
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
            return new CacheEntry(data, Optional.empty(), maxTtl, java.util.Set.of());
        }

        /**
         * Create entry for metadata with blocked versions, detail unknown.
         * Back-compat variant of
         * {@link #withBlockedVersions(byte[], Instant, Duration, java.util.Set)}
         * for callers that do not track which versions were hidden.
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
            return new CacheEntry(data, Optional.of(earliestBlockedUntil), maxTtl, null);
        }

        /**
         * Create entry for metadata with blocked versions.
         * TTL is set to expire when the earliest block expires.
         *
         * @param data Filtered metadata bytes
         * @param earliestBlockedUntil When the earliest block expires
         * @param maxTtl Maximum TTL (used as fallback)
         * @param blockedVersions Versions hidden from the listing by cooldown
         * @return Cache entry
         */
        public static CacheEntry withBlockedVersions(
            final byte[] data,
            final Instant earliestBlockedUntil,
            final Duration maxTtl,
            final java.util.Set<String> blockedVersions
        ) {
            return new CacheEntry(data, Optional.of(earliestBlockedUntil), maxTtl, blockedVersions);
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
