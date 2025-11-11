/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.cache.ValkeyConnection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cache specifically for Maven metadata files (maven-metadata.xml) with configurable TTL.
 * This dramatically reduces upstream requests by caching metadata that changes infrequently.
 * 
 * @since 0.11
 */
public class MetadataCache {
    
    /**
     * Default TTL for metadata cache (24 hours).
     */
    protected static final Duration DEFAULT_TTL = Duration.ofHours(24);
    
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
     * Create metadata cache with default 24h TTL and 10K max size.
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
        this.cache = this.buildCaffeineCache(ttl, maxSize, this.twoTier);
    }

    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    private ValkeyConnection resolveValkeyConnection(final ValkeyConnection valkey) {
        return (valkey != null) 
            ? valkey 
            : com.artipie.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
    }

    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    private Cache<Key, CachedMetadata> buildCaffeineCache(
        final Duration ttl,
        final int maxSize,
        final boolean twoTier
    ) {
        final Duration l1Ttl = twoTier ? Duration.ofMinutes(5) : ttl;
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
            return CompletableFuture.completedFuture(Optional.of(l1Cached.content));
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
                        final String contentStr = new String(l2Bytes, StandardCharsets.UTF_8);
                        final Content content = new Content.From(contentStr.getBytes(StandardCharsets.UTF_8));
                        final CachedMetadata metadata = new CachedMetadata(content, Instant.now());
                        this.cache.put(key, metadata);
                        return CompletableFuture.completedFuture(Optional.of(content));
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
     * Previously, we consumed it for L2 write, then returned the consumed content → error!
     * 
     * New approach:
     * 1. Consume the original content Publisher to get bytes
     * 2. Cache bytes to L2 (Valkey)
     * 3. Create NEW Content from bytes for L1 cache
     * 4. Return NEW Content from bytes to caller
     * 
     * This ensures the content can be read by the caller without errors.
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
                    // Now we have bytes - can create multiple Content instances
                    
                    // Create Content for L1 cache (in-memory)
                    final Content l1Content = new Content.From(bytes);
                    this.cache.put(key, new CachedMetadata(l1Content, Instant.now()));
                    
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
            final String redisKey = "maven:metadata:" + key.string();
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
            final String scanPattern = "maven:metadata:" + prefix + "*";
            this.l2.keys(scanPattern).thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
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
            this.l2.keys("maven:metadata:*").thenAccept(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    this.l2.del(keys.toArray(new String[0]));
                }
            });
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
     * Cached metadata entry with timestamp.
     * Simple wrapper to hold content with its cache time.
     */
    protected static final class CachedMetadata {
        
        /**
         * Cached content.
         */
        private final Content content;
        
        /**
         * Timestamp when cached (for tracking purposes).
         */
        private final Instant timestamp;
        
        /**
         * Create cached metadata entry.
         * @param content Metadata content
         * @param timestamp Timestamp when cached
         */
        CachedMetadata(final Content content, final Instant timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
