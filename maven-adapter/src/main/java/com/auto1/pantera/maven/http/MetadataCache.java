/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cache specifically for Maven metadata files (maven-metadata.xml) with configurable TTL.
 * This dramatically reduces upstream requests by caching metadata that changes infrequently.
 * 
 * @since 0.11
 */
public class MetadataCache {
    
    /**
     * Default TTL for metadata cache (12 hours).
     */
    protected static final Duration DEFAULT_TTL = Duration.ofHours(12);
    
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
     * Time-to-live for cached metadata.
     */
    protected final Duration ttl;
    
    /**
     * Repository name for cache key isolation.
     * Used to prevent cache collisions in group repositories.
     */
    private final String repoName;

    /**
     * Keys currently being refreshed in background (stale-while-revalidate).
     */
    private final ConcurrentHashMap.KeySetView<Key, Boolean> refreshing;
    
    /**
     * Create metadata cache with default 12h TTL and 10K max size.
     */
    public MetadataCache() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, null, "default");
    }
    
    /**
     * Create metadata cache with Valkey connection (two-tier).
     * @param valkey Valkey connection for L2 cache
     */
    public MetadataCache(final ValkeyConnection valkey) {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, valkey, "default");
    }
    
    /**
     * Create metadata cache with custom TTL and default max size.
     * @param ttl Time-to-live for cached metadata
     */
    public MetadataCache(final Duration ttl) {
        this(ttl, DEFAULT_MAX_SIZE, null, "default");
    }
    
    /**
     * Create metadata cache with custom TTL and max size.
     * @param ttl Time-to-live for cached metadata
     * @param maxSize Maximum number of entries (Window TinyLFU eviction)
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     */
    public MetadataCache(
        final Duration ttl, 
        final int maxSize, 
        final ValkeyConnection valkey
    ) {
        this(ttl, maxSize, valkey, "default");
    }
    
    /**
     * Constructor for metadata cache.
     * @param ttl Time-to-live for cached metadata
     * @param maxSize Maximum number of entries in L1 cache
     * @param valkey Valkey connection for L2 cache (null uses GlobalCacheConfig)
     * @param repoName Repository name for cache key isolation
     */
    @SuppressWarnings({"PMD.NullAssignment", "PMD.ConstructorOnlyInitializesOrCallOtherConstructors"})
    public MetadataCache(
        final Duration ttl, 
        final int maxSize, 
        final ValkeyConnection valkey,
        final String repoName
    ) {
        final ValkeyConnection actualValkey = this.resolveValkeyConnection(valkey);
        
        this.ttl = ttl;
        this.twoTier = actualValkey != null;
        this.l2 = this.twoTier ? actualValkey.async() : null;
        this.repoName = repoName != null ? repoName : "default";
        this.refreshing = ConcurrentHashMap.newKeySet();
        this.cache = this.buildCaffeineCache(ttl, maxSize, this.twoTier);
    }

    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    private ValkeyConnection resolveValkeyConnection(final ValkeyConnection valkey) {
        return (valkey != null) 
            ? valkey 
            : com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
    }

    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    private Cache<Key, CachedMetadata> buildCaffeineCache(
        final Duration ttl,
        final int maxSize,
        final boolean twoTier
    ) {
        // Hard expiry = 2x soft TTL to support stale-while-revalidate.
        // Entries stay in cache past soft TTL but get background-refreshed.
        final Duration l1Ttl = twoTier ? Duration.ofMinutes(10) : ttl.multipliedBy(2);
        final int l1Size = twoTier ? Math.max(1000, maxSize / 10) : maxSize;
        return Caffeine.newBuilder()
            .maximumSize(l1Size)
            .expireAfterWrite(l1Ttl.toMillis(), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
    }
    
    /**
     * Load metadata from cache or fetch from remote.
     * Thread-safe - Caffeine handles all synchronization internally.
     * @param key Metadata key
     * @param remote Supplier for fetching from upstream
     * @return Future with optional content
     */
    public CompletableFuture<Optional<Content>> load(
        final Key key,
        final java.util.function.Supplier<CompletableFuture<Optional<Content>>> remote
    ) {
        // L1: Check in-memory cache
        final CachedMetadata l1Cached = this.cache.getIfPresent(key);
        if (l1Cached != null) {
            if (!l1Cached.isStale(this.ttl)) {
                return CompletableFuture.completedFuture(Optional.of(l1Cached.content()));
            }
            // Stale-while-revalidate: past max-stale boundary forces fresh fetch
            if (l1Cached.isStale(this.ttl.multipliedBy(3))) {
                this.cache.invalidate(key);
                this.refreshing.remove(key);
                return this.fetchAndCache(key, remote);
            }
            // Within stale window: serve cached, trigger background refresh
            if (this.refreshing.add(key)) {
                CompletableFuture.runAsync(() ->
                    this.fetchAndCache(key, remote)
                        .whenComplete((res, err) -> this.refreshing.remove(key))
                );
            }
            return CompletableFuture.completedFuture(Optional.of(l1Cached.content()));
        }

        // L2: Check Valkey (if enabled)
        if (this.twoTier) {
            final String redisKey = "maven:metadata:" + this.repoName + ":" + key.string();
            return this.l2.get(redisKey)
                .toCompletableFuture()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(err -> null)
                .thenCompose(l2Bytes -> {
                    if (l2Bytes != null) {
                        // L2 HIT: Deserialize and promote to L1
                        // Store bytes in L1, not Content Publisher
                        final CachedMetadata metadata = new CachedMetadata(l2Bytes, Instant.now());
                        this.cache.put(key, metadata);
                        // Return fresh Content instance
                        return CompletableFuture.completedFuture(Optional.of(metadata.content()));
                    }
                    // L2 MISS: Fetch from remote
                    return this.fetchAndCache(key, remote);
                });
        }

        // Single-tier: Fetch from remote
        return this.fetchAndCache(key, remote);
    }
    
    /**
     * Fetch from remote and cache in both tiers.
     *
     * CRITICAL FIX: Content Publisher can only be consumed once.
     * We consume it once to get bytes, then store bytes (not Publisher) in cache.
     *
     * New approach:
     * 1. Consume the original content Publisher to get bytes
     * 2. Store bytes in L1 cache (not Content Publisher)
     * 3. Cache bytes to L2 (Valkey) if enabled
     * 4. Return NEW Content from bytes to caller
     *
     * This ensures the content can be read by the caller and from cache without errors.
     */
    private CompletableFuture<Optional<Content>> fetchAndCache(
        final Key key,
        final java.util.function.Supplier<CompletableFuture<Optional<Content>>> remote
    ) {
        return remote.get().thenCompose(
            opt -> {
                if (opt.isEmpty()) {
                    // No content from remote - invalidate cache
                    this.cache.invalidate(key);
                    return CompletableFuture.completedFuture(opt);
                }

                final Content content = opt.get();

                // CRITICAL: Consume the content Publisher ONCE to get bytes
                // This is the ONLY read of the original Publisher
                return content.asBytesFuture().thenApply(bytes -> {
                    // Now we have bytes - store in L1 cache
                    // CRITICAL: Store bytes, not Content Publisher
                    this.cache.put(key, new CachedMetadata(bytes, Instant.now()));

                    // Cache bytes in L2 (Valkey) if enabled
                    if (this.twoTier) {
                        final String redisKey = "maven:metadata:" + this.repoName + ":" + key.string();
                        final long seconds = this.ttl.getSeconds();
                        // Fire-and-forget write to Valkey (don't block on it)
                        this.l2.setex(redisKey, seconds, bytes);
                    }

                    // Return NEW Content from bytes to caller
                    // This ensures caller can read the content without "already consumed" errors
                    return Optional.of(new Content.From(bytes));
                });
            }
        );
    }
    
    /**
     * Invalidate specific metadata entry (e.g., after upload).
     * Thread-safe - Caffeine handles synchronization.
     * @param key Key to invalidate
     */
    public void invalidate(final Key key) {
        // Invalidate L1
        this.cache.invalidate(key);
        
        // Invalidate L2 (if enabled)
        if (this.twoTier) {
            final String redisKey = "maven:metadata:" + this.repoName + ":" + key.string();
            this.l2.del(redisKey);
        }
    }
    
    /**
     * Invalidate all metadata entries matching a pattern (e.g., for a specific artifact).
     * Thread-safe - Caffeine handles synchronization.
     * @param prefix Key prefix to match (e.g., "com/example/artifact/")
     */
    public void invalidatePrefix(final String prefix) {
        // Invalidate L1
        this.cache.asMap().keySet().removeIf(key -> key.string().startsWith(prefix));
        
        // Invalidate L2 (if enabled)
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
        // Clear L1
        this.cache.invalidateAll();
        
        // Clear L2 (if enabled)
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
     * Cached metadata entry with timestamp.
     *
     * CRITICAL: Stores bytes instead of Content Publisher.
     * Content is a Publisher that can only be consumed once.
     * By storing bytes, we can create fresh Content instances on each cache hit.
     */
    protected static final class CachedMetadata {

        /**
         * Cached content as bytes (not Publisher).
         * This allows creating fresh Content instances on each cache hit.
         */
        private final byte[] bytes;

        /**
         * Timestamp when cached (for tracking purposes).
         */
        private final Instant timestamp;

        /**
         * Create cached metadata entry.
         * @param bytes Metadata content as bytes
         * @param timestamp Timestamp when cached
         */
        CachedMetadata(final byte[] bytes, final Instant timestamp) {
            // Clone array to prevent external modification (PMD: ArrayIsStoredDirectly)
            this.bytes = bytes.clone();
            this.timestamp = timestamp;
        }

        /**
         * Get content as fresh Publisher.
         * Creates a new Content instance each time to avoid "already consumed" errors.
         * @return Fresh Content instance
         */
        Content content() {
            return new Content.From(this.bytes);
        }

        /**
         * Check if this entry is past the soft TTL (stale-while-revalidate).
         * @param softTtl Soft TTL duration
         * @return True if entry age exceeds soft TTL
         */
        boolean isStale(final Duration softTtl) {
            return Duration.between(this.timestamp, Instant.now()).compareTo(softTtl) > 0;
        }
    }
}
