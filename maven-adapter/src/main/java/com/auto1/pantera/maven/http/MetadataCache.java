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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cache specifically for Maven metadata files (maven-metadata.xml) with
 * configurable soft / hard TTLs, conditional-GET validators, and
 * single-flighted stale-while-revalidate refreshes.
 *
 * <p>T-P10 (conditional GET): cached entries persist the upstream {@code
 * ETag} and {@code Last-Modified} alongside the bytes. The new
 * {@link #load(Key, Function)} overload takes a {@link ConditionalRemote}
 * which is handed the stored validators and may return one of:
 * {@link MetadataFetchResult#modified(byte[], String, String)} (200 with
 * new bytes, replace cache), {@link MetadataFetchResult#unmodified()}
 * (304 — bump {@code lastVerified} only, no blob rewrite), or
 * {@link MetadataFetchResult#notFound()} (404 — clear the entry).</p>
 *
 * <p>T-P11 (stale-while-revalidate): two TTLs control refresh behaviour:
 * <ul>
 *   <li><b>Soft TTL</b> (default 30 s). Within this window every request
 *       serves cached bytes with zero upstream I/O.</li>
 *   <li><b>Hard TTL</b> (default 2 h). Between the soft TTL and the hard
 *       TTL, requests still serve cached bytes <i>but</i> trigger a
 *       background single-flighted refresh. After the hard TTL the cache
 *       blocks on the upstream fetcher (cold-miss behaviour).</li>
 * </ul>
 * The single-flight is scoped per cache instance — N concurrent foreground
 * staleness-triggered refreshes for the same key collapse to one upstream
 * call per (repo, key) per soft-TTL window.</p>
 *
 * @since 0.11
 */
public class MetadataCache {

    /**
     * Default soft TTL (30 s). Within this window we serve from cache
     * without contacting upstream.
     */
    protected static final Duration DEFAULT_SOFT_TTL = Duration.ofSeconds(30);

    /**
     * Default hard TTL (2 h). After this window we block on upstream rather
     * than serving stale bytes — the cached entry is past its
     * stale-while-revalidate budget.
     */
    protected static final Duration DEFAULT_HARD_TTL = Duration.ofHours(2);

    /**
     * Default maximum cache size (10,000 entries).
     * At ~5KB per metadata file = ~50MB maximum memory usage.
     */
    protected static final int DEFAULT_MAX_SIZE = 10_000;

    /**
     * L1 cache with Window TinyLFU eviction (better than LRU).
     * Thread-safe, high-performance, with built-in statistics.
     */
    protected final Cache<Key, CachedMetadata> cache;

    /**
     * L2 cache (Valkey/Redis, warm data) - optional.
     */
    private final RedisAsyncCommands<String, byte[]> l2;

    /**
     * Whether two-tier caching is enabled.
     */
    private final boolean twoTier;

    /**
     * Soft TTL — within this window of {@code lastVerified}, requests serve
     * from cache with no upstream call.
     */
    protected final Duration softTtl;

    /**
     * Hard TTL — past this window of {@code lastVerified}, requests block
     * on upstream rather than serving stale bytes.
     */
    protected final Duration hardTtl;

    /**
     * Repository name for cache key isolation.
     * Used to prevent cache collisions in group repositories.
     */
    private final String repoName;

    /**
     * Clock for {@code lastVerified} stamping and stale-window checks.
     * Injectable so unit tests can time-travel without thread sleeps.
     */
    private final Clock clock;

    /**
     * Single-flight coalescer for SWR background refreshes. N concurrent
     * staleness-triggered refreshes for the same key produce one upstream
     * call per soft-TTL window.
     */
    private final SingleFlight<Key, Void> swrRefresh;

    /**
     * Create metadata cache with default soft TTL (30 s) / hard TTL (2 h).
     */
    public MetadataCache() {
        this(DEFAULT_SOFT_TTL, DEFAULT_HARD_TTL, DEFAULT_MAX_SIZE, null,
            "default", Clock.systemUTC());
    }

    /**
     * Create metadata cache with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     */
    public MetadataCache(final ValkeyConnection valkey) {
        this(DEFAULT_SOFT_TTL, DEFAULT_HARD_TTL, DEFAULT_MAX_SIZE, valkey,
            "default", Clock.systemUTC());
    }

    /**
     * Create metadata cache with custom soft TTL.
     *
     * <p>Backward-compatible single-TTL constructor. The supplied {@code
     * softTtl} is also used as soft TTL; hard TTL is derived as {@code
     * softTtl.multipliedBy(4)} to retain the pre-T-P11 stale-window
     * shape (cap at 2 h, min 100 ms for short-TTL tests).
     *
     * @param softTtl Soft TTL — fresh window for cache fast-path.
     */
    public MetadataCache(final Duration softTtl) {
        this(
            softTtl,
            deriveHardTtl(softTtl),
            DEFAULT_MAX_SIZE,
            null,
            "default",
            Clock.systemUTC()
        );
    }

    /**
     * Create metadata cache with explicit soft / hard TTLs.
     * @param softTtl Soft TTL — fresh window.
     * @param hardTtl Hard TTL — stale-while-revalidate budget.
     */
    public MetadataCache(final Duration softTtl, final Duration hardTtl) {
        this(softTtl, hardTtl, DEFAULT_MAX_SIZE, null, "default",
            Clock.systemUTC());
    }

    /**
     * Create metadata cache with custom TTL and max size.
     * @param ttl Time-to-live for cached metadata (soft TTL)
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     */
    public MetadataCache(
        final Duration ttl,
        final int maxSize,
        final ValkeyConnection valkey
    ) {
        this(ttl, deriveHardTtl(ttl), maxSize, valkey, "default",
            Clock.systemUTC());
    }

    /**
     * Constructor for metadata cache with backward-compatible single TTL.
     * Hard TTL is derived from {@code ttl} (see {@link #deriveHardTtl}).
     * @param ttl Soft TTL for cached metadata.
     * @param maxSize Maximum number of entries in L1 cache.
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig).
     * @param repoName Repository name for cache key isolation.
     */
    public MetadataCache(
        final Duration ttl,
        final int maxSize,
        final ValkeyConnection valkey,
        final String repoName
    ) {
        this(ttl, deriveHardTtl(ttl), maxSize, valkey, repoName,
            Clock.systemUTC());
    }

    /**
     * Full constructor with explicit soft / hard TTLs and injectable clock.
     *
     * <p>This is the canonical constructor — all other public constructors
     * delegate here. The clock is injected so {@link MetadataCache} can be
     * unit-tested without thread sleeps; production callers should pass
     * {@link Clock#systemUTC()}.</p>
     *
     * @param softTtl Soft TTL — fresh window for cache fast-path.
     * @param hardTtl Hard TTL — stale-while-revalidate budget.
     * @param maxSize Maximum number of entries in L1 cache.
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig).
     * @param repoName Repository name for cache key isolation.
     * @param clock Clock for {@code lastVerified} stamping.
     */
    public MetadataCache(
        final Duration softTtl,
        final Duration hardTtl,
        final int maxSize,
        final ValkeyConnection valkey,
        final String repoName,
        final Clock clock
    ) {
        validateTtls(softTtl, hardTtl);
        final ValkeyConnection actualValkey = this.resolveValkeyConnection(valkey);
        this.softTtl = softTtl;
        this.hardTtl = hardTtl;
        this.twoTier = actualValkey != null;
        this.l2 = this.twoTier ? actualValkey.async() : null;
        this.repoName = repoName != null ? repoName : "default";
        this.clock = clock;
        this.cache = this.buildCaffeineCache(hardTtl, maxSize, this.twoTier);
        this.swrRefresh = new SingleFlight<>(
            // Inflight TTL: bounded by hard TTL but capped at 10 min so a
            // truly stuck loader doesn't accumulate forever. The
            // single-flight only holds in-flight state — completed loads
            // are evicted immediately.
            cap(hardTtl, Duration.ofMinutes(10)),
            10_000,
            ForkJoinPool.commonPool()
        );
    }

    /**
     * Derive a hard TTL from a single-TTL legacy constructor argument. Pre-
     * T-P11 the cache used {@code ttl.multipliedBy(2)} for L1 expireAfterWrite
     * and {@code ttl.multipliedBy(3)} for the max-stale boundary. Bring those
     * together as a 4x multiplier for the new hard TTL: keeps existing tests
     * stable while giving callers headroom to refresh in the background.
     */
    private static Duration deriveHardTtl(final Duration softTtl) {
        final Duration hard = softTtl.multipliedBy(4);
        // Never below 100 ms so short-TTL stale-window tests still observe a
        // window distinct from the soft TTL.
        if (hard.compareTo(Duration.ofMillis(100)) < 0) {
            return Duration.ofMillis(100);
        }
        return hard;
    }

    private static Duration cap(final Duration value, final Duration max) {
        return value.compareTo(max) > 0 ? max : value;
    }

    private static void validateTtls(final Duration softTtl, final Duration hardTtl) {
        if (softTtl == null || softTtl.isNegative() || softTtl.isZero()) {
            throw new IllegalArgumentException(
                "softTtl must be strictly positive: " + softTtl
            );
        }
        if (hardTtl == null || hardTtl.compareTo(softTtl) < 0) {
            throw new IllegalArgumentException(
                "hardTtl must be >= softTtl (soft=" + softTtl
                    + ", hard=" + hardTtl + ")"
            );
        }
    }

    private ValkeyConnection resolveValkeyConnection(final ValkeyConnection valkey) {
        return (valkey != null)
            ? valkey
            : com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
    }

    private Cache<Key, CachedMetadata> buildCaffeineCache(
        final Duration hardTtl,
        final int maxSize,
        final boolean twoTier
    ) {
        // Caffeine expireAfterWrite is the hard CONTAINER bound: entries
        // must outlive {@code hardTtl} so that the hard-TTL fall-through
        // path in {@link #load} can still see the cached validators (and
        // serve the cached bytes on a 304). We hold entries for 4x hardTtl
        // so a 304 -> lastVerified bump can take us back into the fresh
        // window without re-fetching the blob. Past 4x the entry is
        // assumed irrelevant and is evicted.
        final Duration l1Ttl;
        if (twoTier) {
            l1Ttl = Duration.ofMinutes(10);
        } else {
            // Bound by 4x hardTtl, but with a minimum of 1 minute so very
            // short-TTL test caches still hold their bytes long enough for
            // the hard-TTL fall-through to observe them.
            final Duration scaled = hardTtl.multipliedBy(4);
            l1Ttl = scaled.compareTo(Duration.ofMinutes(1)) < 0
                ? Duration.ofMinutes(1) : scaled;
        }
        final int l1Size = twoTier ? Math.max(1000, maxSize / 10) : maxSize;
        return Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
    }

    /**
     * Legacy load path — returns {@code Optional<Content>} from a non-
     * conditional supplier. Wraps the supplier in a
     * {@link ConditionalRemote} that ignores the validators and treats every
     * response as a 200 (no 304 fast-path).
     *
     * <p>Retained for callers that have not migrated to the conditional API
     * (e.g., legacy tests). New code paths should call
     * {@link #load(Key, Function)}.</p>
     *
     * @param key Metadata key.
     * @param remote Non-conditional remote supplier.
     * @return Future with cached or fetched content; empty when upstream
     *         and cache both have nothing.
     */
    public CompletableFuture<Optional<Content>> load(
        final Key key,
        final Supplier<CompletableFuture<Optional<Content>>> remote
    ) {
        return this.load(
            key,
            unused -> remote.get().thenApply(
                opt -> opt.map(content -> MetadataFetchResult.modifiedFromContent(content, null, null))
                    .orElseGet(MetadataFetchResult::notFound)
            )
        );
    }

    /**
     * Conditional load — the canonical entry point.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Cache miss: hands the loader an empty {@link ConditionalRequest}
     *       and waits for the result. On 200 → cache + serve; on 404 → clear
     *       and return empty; on 304 (unexpected on cold miss) → treat as
     *       empty.</li>
     *   <li>Within soft TTL: serve cached, no loader call.</li>
     *   <li>Within stale window (soft &lt; age &lt;= hard): serve cached;
     *       fire a single-flighted background refresh with the cached
     *       validators. On 200 the cache is replaced; on 304 the
     *       {@code lastVerified} is bumped without rewriting the blob.</li>
     *   <li>Past hard TTL: block on the loader with cached validators
     *       (an opportunistic 304 still bumps {@code lastVerified} and
     *       serves the cached blob).</li>
     * </ul>
     *
     * @param key Metadata key.
     * @param remote Conditional loader.
     * @return Future with cached or fetched content; empty when upstream
     *         returns 404 and there is no usable cached entry.
     */
    public CompletableFuture<Optional<Content>> load(
        final Key key,
        final ConditionalRemote remote
    ) {
        final CachedMetadata l1Cached = this.cache.getIfPresent(key);
        if (l1Cached != null) {
            final Duration age = Duration.between(l1Cached.lastVerified, Instant.now(this.clock));
            if (age.compareTo(this.softTtl) <= 0) {
                // Fresh window — pure cache hit, no upstream call.
                return CompletableFuture.completedFuture(Optional.of(l1Cached.content()));
            }
            if (age.compareTo(this.hardTtl) <= 0) {
                // Stale window — serve cached AND fire single-flighted
                // background refresh. SingleFlight collapses concurrent
                // staleness-triggered refreshes for the same key into one
                // upstream call. We do not wait for the refresh.
                this.swrRefresh.load(key, () -> this.fetchAndCache(key, l1Cached, remote));
                return CompletableFuture.completedFuture(Optional.of(l1Cached.content()));
            }
            // Past hard TTL — entry is too old to serve stale. Block on
            // upstream with the cached validators so a 304 still avoids
            // a blob rewrite (and preserves lastVerified bump semantics).
            return this.fetchAndCacheBlocking(key, l1Cached, remote);
        }
        // L2: Check Valkey (if enabled)
        if (this.twoTier) {
            final String redisKey = this.l2Key(key);
            return this.l2.get(redisKey)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> null)
                .thenCompose(l2Bytes -> {
                    if (l2Bytes != null) {
                        // L2 HIT: Promote to L1 with current timestamp. The
                        // ETag/Last-Modified are not persisted in L2 (the
                        // value is bytes-only) — a deliberate trade-off:
                        // L2 acts as a warm read-through cache and the
                        // first L1 SWR refresh will re-populate validators.
                        final CachedMetadata metadata = new CachedMetadata(
                            l2Bytes, null, null, Instant.now(this.clock)
                        );
                        this.cache.put(key, metadata);
                        return CompletableFuture.completedFuture(Optional.of(metadata.content()));
                    }
                    return this.fetchAndCacheBlocking(key, null, remote);
                });
        }
        // Single-tier cold miss: block on upstream.
        return this.fetchAndCacheBlocking(key, null, remote);
    }

    private String l2Key(final Key key) {
        return "maven:metadata:" + this.repoName + ":" + key.string();
    }

    /**
     * Block on the upstream fetcher: cold miss or hard-TTL fallthrough.
     */
    private CompletableFuture<Optional<Content>> fetchAndCacheBlocking(
        final Key key,
        final CachedMetadata existing,
        final ConditionalRemote remote
    ) {
        return this.fetchAndApply(key, existing, remote)
            .thenApply(updated ->
                updated == null
                    ? Optional.<Content>empty()
                    : Optional.of(updated.content())
            );
    }

    /**
     * Background refresh path: dispatches the loader, applies the result to
     * the cache, ignores the return value. Returns a {@code Void} so it can
     * be wrapped in {@link SingleFlight#load}.
     */
    private CompletionStage<Void> fetchAndCache(
        final Key key,
        final CachedMetadata existing,
        final ConditionalRemote remote
    ) {
        return this.fetchAndApply(key, existing, remote)
            .thenApply(ignored -> null);
    }

    /**
     * Invoke the loader with the cached validators (if any) and apply the
     * result to the cache. Returns the updated {@link CachedMetadata} on
     * 200/304 or {@code null} when the entry was cleared (404 or upstream
     * absence).
     */
    private CompletableFuture<CachedMetadata> fetchAndApply(
        final Key key,
        final CachedMetadata existing,
        final ConditionalRemote remote
    ) {
        final ConditionalRequest req = new ConditionalRequest(
            existing == null ? null : existing.etag,
            existing == null ? null : existing.lastModified
        );
        return remote.fetch(req).thenCompose(result -> this.applyResult(key, existing, result));
    }

    private CompletableFuture<CachedMetadata> applyResult(
        final Key key,
        final CachedMetadata existing,
        final MetadataFetchResult result
    ) {
        return switch (result.kind()) {
            case MODIFIED -> this.applyModified(key, result);
            case UNMODIFIED -> this.applyUnmodified(key, existing);
            case NOT_FOUND -> this.applyNotFound(key);
        };
    }

    private CompletableFuture<CachedMetadata> applyModified(
        final Key key,
        final MetadataFetchResult result
    ) {
        final CompletableFuture<byte[]> bytesFuture;
        if (result.bytes() != null) {
            bytesFuture = CompletableFuture.completedFuture(result.bytes());
        } else if (result.content() != null) {
            bytesFuture = result.content().asBytesFuture();
        } else {
            bytesFuture = CompletableFuture.completedFuture(new byte[0]);
        }
        return bytesFuture.thenApply(bytes -> {
            final CachedMetadata updated = new CachedMetadata(
                bytes, result.etag(), result.lastModified(),
                Instant.now(this.clock)
            );
            this.cache.put(key, updated);
            if (this.twoTier) {
                final String redisKey = this.l2Key(key);
                final long seconds = this.hardTtl.getSeconds();
                this.l2.setex(redisKey, seconds, bytes);
            }
            return updated;
        });
    }

    private CompletableFuture<CachedMetadata> applyUnmodified(
        final Key key,
        final CachedMetadata existing
    ) {
        if (existing == null) {
            // Upstream returned 304 but we have nothing cached — treat as
            // empty. This is an upstream bug (304 without prior ETag) but
            // we should not put garbage in the cache.
            return CompletableFuture.completedFuture(null);
        }
        // Bump lastVerified WITHOUT rewriting bytes / validators. The
        // existing entry stays in the L1 cache; we replace the entry with
        // an updated lastVerified stamp so the soft/hard TTL windows reset
        // from "now".
        final CachedMetadata refreshed = new CachedMetadata(
            existing.bytes, existing.etag, existing.lastModified,
            Instant.now(this.clock)
        );
        this.cache.put(key, refreshed);
        return CompletableFuture.completedFuture(refreshed);
    }

    private CompletableFuture<CachedMetadata> applyNotFound(final Key key) {
        this.cache.invalidate(key);
        if (this.twoTier) {
            this.l2.del(this.l2Key(key));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate specific metadata entry (e.g., after upload).
     * Thread-safe - Caffeine handles synchronization.
     * @param key Key to invalidate
     */
    public void invalidate(final Key key) {
        this.cache.invalidate(key);
        if (this.twoTier) {
            this.l2.del(this.l2Key(key));
        }
    }

    /**
     * Invalidate all metadata entries matching a pattern (e.g., for a specific artifact).
     * Thread-safe - Caffeine handles synchronization.
     * @param prefix Key prefix to match (e.g., "com/example/artifact/")
     */
    public void invalidatePrefix(final String prefix) {
        this.cache.asMap().keySet().removeIf(key -> key.string().startsWith(prefix));
        if (this.twoTier) {
            final String scanPattern = "maven:metadata:" + this.repoName + ":" + prefix + "*";
            this.scanAndDelete(scanPattern);
        }
    }

    /**
     * Clear entire cache.
     * Thread-safe - Caffeine handles synchronization.
     * Useful for testing or manual cache invalidation.
     */
    public void clear() {
        this.cache.invalidateAll();
        if (this.twoTier) {
            this.scanAndDelete("maven:metadata:" + this.repoName + ":*");
        }
    }

    /**
     * Remove expired entries (periodic cleanup).
     * Note: Caffeine handles expiry automatically, but calling this
     * triggers immediate cleanup instead of lazy removal.
     */
    public void cleanup() {
        this.cache.cleanUp();
    }

    /**
     * Get cache statistics from Caffeine.
     * Includes hit rate, miss rate, eviction count, etc.
     * @return Caffeine cache statistics
     */
    public CacheStats stats() {
        return this.cache.stats();
    }

    /**
     * Get current cache size.
     * @return Number of entries in cache
     */
    public long size() {
        return this.cache.estimatedSize();
    }

    /**
     * Scan and delete keys matching pattern using cursor-based SCAN.
     * Avoids blocking KEYS command that freezes Redis on large datasets.
     * @param pattern Redis key pattern (glob-style)
     */
    private CompletableFuture<Void> scanAndDelete(final String pattern) {
        return this.scanAndDeleteStep(ScanCursor.INITIAL, pattern);
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
                return this.scanAndDeleteStep(result, pattern);
            });
    }

    /**
     * Conditional-GET loader contract — hands the cached validators to
     * the upstream caller so they can be sent as {@code If-None-Match} /
     * {@code If-Modified-Since}.
     */
    @FunctionalInterface
    public interface ConditionalRemote {
        /**
         * Fetch the metadata from upstream, optionally honouring the
         * stored validators.
         *
         * @param request Cached validators (may be empty on cold miss).
         * @return Future with the upstream result.
         */
        CompletableFuture<MetadataFetchResult> fetch(ConditionalRequest request);
    }

    /**
     * Cached validators handed to the {@link ConditionalRemote}. Either
     * field may be {@code null} when the cache has no record (cold miss
     * or upstream omitted the header on the previous fetch).
     */
    public static final class ConditionalRequest {
        private final String etag;
        private final String lastModified;

        public ConditionalRequest(final String etag, final String lastModified) {
            this.etag = etag;
            this.lastModified = lastModified;
        }

        /**
         * @return The stored ETag, or {@code null} if none.
         */
        public Optional<String> etag() {
            return Optional.ofNullable(this.etag);
        }

        /**
         * @return The stored Last-Modified value (in the format upstream
         *         supplied — typically RFC-1123), or {@code null} if none.
         */
        public Optional<String> lastModified() {
            return Optional.ofNullable(this.lastModified);
        }

        public boolean isEmpty() {
            return this.etag == null && this.lastModified == null;
        }
    }

    /**
     * Loader result variants for the conditional fetch.
     */
    public static final class MetadataFetchResult {

        /**
         * Result kind discriminator.
         */
        public enum Kind { MODIFIED, UNMODIFIED, NOT_FOUND }

        private final Kind kind;
        private final byte[] bytes;
        private final Content content;
        private final String etag;
        private final String lastModified;

        private MetadataFetchResult(
            final Kind kind,
            final byte[] bytes,
            final Content content,
            final String etag,
            final String lastModified
        ) {
            this.kind = kind;
            // Defensive copy — same convention as CachedMetadata. May be
            // null when the caller supplied a Content publisher instead.
            this.bytes = bytes == null ? null : bytes.clone();
            this.content = content;
            this.etag = etag;
            this.lastModified = lastModified;
        }

        /**
         * 200 OK from upstream with new bytes.
         * @param bytes Response body bytes (must be non-null).
         * @param etag Upstream ETag (nullable).
         * @param lastModified Upstream Last-Modified (nullable).
         * @return Modified result.
         */
        public static MetadataFetchResult modified(
            final byte[] bytes, final String etag, final String lastModified
        ) {
            return new MetadataFetchResult(Kind.MODIFIED, bytes, null, etag, lastModified);
        }

        /**
         * 200 OK from upstream wrapping a Content publisher. The bytes are
         * drained when the cache applies the result.
         * @param content Response body publisher (must be non-null).
         * @param etag Upstream ETag (nullable).
         * @param lastModified Upstream Last-Modified (nullable).
         * @return Modified result.
         */
        public static MetadataFetchResult modifiedFromContent(
            final Content content, final String etag, final String lastModified
        ) {
            return new MetadataFetchResult(Kind.MODIFIED, null, content, etag, lastModified);
        }

        /**
         * 304 Not Modified — cache stays, lastVerified bumps.
         * @return Unmodified result.
         */
        public static MetadataFetchResult unmodified() {
            return new MetadataFetchResult(Kind.UNMODIFIED, null, null, null, null);
        }

        /**
         * 404 Not Found / upstream absence — clear cache.
         * @return NotFound result.
         */
        public static MetadataFetchResult notFound() {
            return new MetadataFetchResult(Kind.NOT_FOUND, null, null, null, null);
        }

        public Kind kind() {
            return this.kind;
        }

        byte[] bytes() {
            return this.bytes == null ? null : this.bytes.clone();
        }

        Content content() {
            return this.content;
        }

        String etag() {
            return this.etag;
        }

        String lastModified() {
            return this.lastModified;
        }
    }

    /**
     * Cached metadata entry with bytes + conditional validators +
     * lastVerified timestamp.
     *
     * <p>Bytes are stored (not the Content publisher) so a single cache
     * entry can be served to many concurrent callers — each {@link #content}
     * call wraps the bytes in a fresh {@link Content.From}.</p>
     */
    protected static final class CachedMetadata {

        private final byte[] bytes;
        private final String etag;
        private final String lastModified;
        private final Instant lastVerified;

        CachedMetadata(
            final byte[] bytes,
            final String etag,
            final String lastModified,
            final Instant lastVerified
        ) {
            this.bytes = bytes.clone();
            this.etag = etag;
            this.lastModified = lastModified;
            this.lastVerified = lastVerified;
        }

        Content content() {
            return new Content.From(this.bytes);
        }

        /**
         * @return Stored ETag (may be {@code null}).
         */
        String etag() {
            return this.etag;
        }

        /**
         * @return Stored Last-Modified (may be {@code null}).
         */
        String lastModified() {
            return this.lastModified;
        }

        /**
         * @return Instant of the last successful upstream contact (200 or 304).
         */
        Instant lastVerified() {
            return this.lastVerified;
        }
    }
}
