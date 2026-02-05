/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.log.EcsLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Write-through cache with TTL support.
 * <p>
 * Writes content to storage immediately (write-through) and tracks metadata
 * for TTL-based expiration and background refresh.
 * </p>
 *
 * @since 1.0
 */
public final class StorageCache {

    /**
     * Underlying storage.
     */
    private final Storage storage;

    /**
     * Cache policy.
     */
    private final CachePolicy policy;

    /**
     * Background refresh service.
     */
    private final BackgroundRefresh refresher;

    /**
     * Cache metadata (timestamps for TTL tracking).
     */
    private final ConcurrentMap<String, CacheEntry> metadata;

    /**
     * Create storage cache with default refresh executor.
     *
     * @param storage Underlying storage
     * @param policy Cache policy
     */
    public StorageCache(final Storage storage, final CachePolicy policy) {
        this(storage, policy, new BackgroundRefresh());
    }

    /**
     * Create storage cache with custom refresher.
     *
     * @param storage Underlying storage
     * @param policy Cache policy
     * @param refresher Background refresh service
     */
    public StorageCache(
        final Storage storage,
        final CachePolicy policy,
        final BackgroundRefresh refresher
    ) {
        this.storage = storage;
        this.policy = policy;
        this.refresher = refresher;
        this.metadata = new ConcurrentHashMap<>();
    }

    /**
     * Get content from cache or fetch from upstream.
     *
     * @param key Storage key
     * @param upstream Upstream fetcher (called on cache miss or refresh)
     * @return Content from cache or upstream
     */
    public CompletableFuture<Optional<Content>> get(
        final Key key,
        final UpstreamFetcher upstream
    ) {
        final String path = key.string();
        final CacheEntry entry = this.metadata.get(path);

        // Check if we have cached content
        return this.storage.exists(key).thenCompose(exists -> {
            if (exists && entry != null) {
                return this.handleCacheHit(key, entry, upstream);
            } else {
                return this.handleCacheMiss(key, upstream);
            }
        });
    }

    /**
     * Save content to cache (write-through).
     *
     * @param key Storage key
     * @param content Content to save
     * @return Future completing when save is done
     */
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final String path = key.string();

        EcsLogger.debug("com.artipie.cache")
            .message(String.format("Saving to cache (is_metadata=%b)", this.policy.isMetadata(path)))
            .eventCategory("cache")
            .eventAction("cache_save")
            .field("url.path", path)
            .log();

        // Write-through: save immediately to storage
        return this.storage.save(key, content).thenRun(() -> {
            // Track metadata for TTL
            this.metadata.put(path, new CacheEntry(Instant.now()));
        });
    }

    /**
     * Check if key exists in cache and is not stale.
     *
     * @param key Storage key
     * @return true if cached and fresh
     */
    public CompletableFuture<Boolean> isFresh(final Key key) {
        final String path = key.string();
        final CacheEntry entry = this.metadata.get(path);

        if (entry == null) {
            return CompletableFuture.completedFuture(false);
        }

        return this.storage.exists(key).thenApply(exists -> {
            if (!exists) {
                return false;
            }
            final Duration age = entry.age();
            final Duration ttl = this.policy.ttl(path);
            // TTL of 0 means cache forever (artifacts)
            return ttl.isZero() || age.compareTo(ttl) < 0;
        });
    }

    /**
     * Invalidate cache entry.
     *
     * @param key Storage key
     * @return Future completing when invalidation is done
     */
    public CompletableFuture<Void> invalidate(final Key key) {
        final String path = key.string();

        EcsLogger.debug("com.artipie.cache")
            .message("Invalidating cache entry")
            .eventCategory("cache")
            .eventAction("cache_invalidate")
            .field("url.path", path)
            .log();

        this.metadata.remove(path);
        return this.storage.delete(key);
    }

    /**
     * Handle cache hit - check freshness and potentially trigger refresh.
     */
    private CompletableFuture<Optional<Content>> handleCacheHit(
        final Key key,
        final CacheEntry entry,
        final UpstreamFetcher upstream
    ) {
        final String path = key.string();
        final Duration age = entry.age();
        final Duration ttl = this.policy.ttl(path);

        // Artifacts (TTL = 0) are always fresh
        if (ttl.isZero()) {
            EcsLogger.debug("com.artipie.cache")
                .message("Cache hit (artifact, cached forever)")
                .eventCategory("cache")
                .eventAction("cache_hit")
                .eventOutcome("success")
                .field("url.path", path)
                .log();
            return this.storage.value(key).thenApply(Optional::of);
        }

        // Check if content is fresh
        if (age.compareTo(ttl) < 0) {
            // Check if we should trigger background refresh
            if (this.policy.shouldRefreshInBackground(path, age)) {
                EcsLogger.debug("com.artipie.cache")
                    .message(String.format("Cache hit (refresh zone, triggering background refresh, age_ms=%d, ttl_ms=%d)", age.toMillis(), ttl.toMillis()))
                    .eventCategory("cache")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("url.path", path)
                    .log();

                // Trigger background refresh
                this.refresher.refresh(key, () ->
                    upstream.fetch().thenCompose(content ->
                        content.map(c -> this.save(key, c).thenApply(v -> content))
                            .orElse(CompletableFuture.completedFuture(Optional.empty()))
                    )
                );
            } else {
                EcsLogger.debug("com.artipie.cache")
                    .message(String.format("Cache hit (fresh, age_ms=%d)", age.toMillis()))
                    .eventCategory("cache")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("url.path", path)
                    .log();
            }
            return this.storage.value(key).thenApply(Optional::of);
        }

        // Content is stale - check if we can serve stale while refreshing
        final Duration staleDuration = this.policy.staleDuration(path);
        if (age.compareTo(staleDuration) < 0) {
            EcsLogger.debug("com.artipie.cache")
                .message(String.format("Cache hit (stale, serving while refreshing, age_ms=%d)", age.toMillis()))
                .eventCategory("cache")
                .eventAction("cache_hit")
                .eventOutcome("stale")
                .field("url.path", path)
                .log();

            // Serve stale and refresh in background
            this.refresher.refresh(key, () ->
                upstream.fetch().thenCompose(content ->
                    content.map(c -> this.save(key, c).thenApply(v -> content))
                        .orElse(CompletableFuture.completedFuture(Optional.empty()))
                )
            );
            return this.storage.value(key).thenApply(Optional::of);
        }

        // Content is too stale - fetch fresh
        EcsLogger.debug("com.artipie.cache")
            .message(String.format("Cache expired, fetching fresh (age_ms=%d)", age.toMillis()))
            .eventCategory("cache")
            .eventAction("cache_miss")
            .eventOutcome("expired")
            .field("url.path", path)
            .log();

        return this.handleCacheMiss(key, upstream);
    }

    /**
     * Handle cache miss - fetch from upstream and save.
     */
    private CompletableFuture<Optional<Content>> handleCacheMiss(
        final Key key,
        final UpstreamFetcher upstream
    ) {
        final String path = key.string();

        EcsLogger.debug("com.artipie.cache")
            .message("Cache miss, fetching from upstream")
            .eventCategory("cache")
            .eventAction("cache_miss")
            .field("url.path", path)
            .log();

        return upstream.fetch().thenCompose(content -> {
            if (content.isPresent()) {
                // Save to cache (write-through)
                return this.save(key, content.get())
                    .thenApply(v -> content);
            }
            return CompletableFuture.completedFuture(content);
        });
    }

    /**
     * Cache entry with timestamp.
     */
    private static final class CacheEntry {
        private final Instant timestamp;

        CacheEntry(final Instant timestamp) {
            this.timestamp = timestamp;
        }

        Duration age() {
            return Duration.between(this.timestamp, Instant.now());
        }
    }

    /**
     * Upstream fetcher interface.
     */
    @FunctionalInterface
    public interface UpstreamFetcher {
        /**
         * Fetch content from upstream.
         *
         * @return Content or empty if not found
         */
        CompletableFuture<Optional<Content>> fetch();
    }
}
