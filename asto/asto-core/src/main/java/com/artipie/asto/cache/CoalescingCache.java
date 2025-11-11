/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache decorator that coalesces concurrent requests for the same key.
 * When multiple requests come in for the same uncached item, only the first
 * request fetches from remote while others wait for that result.
 * This prevents cache stampede where N concurrent requests cause N upstream
 * fetches.
 *
 * @since 1.18.14
 */
public final class CoalescingCache implements Cache {

    /**
     * Underlying cache implementation.
     */
    private final Cache cache;

    /**
     * In-flight requests being processed.
     * Maps cache key to the future that will complete when fetch finishes.
     */
    private final ConcurrentHashMap<Key, CompletableFuture<Optional<? extends Content>>> inflight;

    /**
     * Wrap an existing cache with request coalescing.
     *
     * @param cache Underlying cache to wrap
     */
    public CoalescingCache(final Cache cache) {
        this.cache = cache;
        this.inflight = new ConcurrentHashMap<>();
    }

    @Override
    public CompletionStage<Optional<? extends Content>> load(
            final Key key,
            final Remote remote,
            final CacheControl control) {
        // Use computeIfAbsent to atomically check and create in-flight request
        return this.inflight.computeIfAbsent(key, k -> {
            // We won the race - start the fetch and convert to CompletableFuture
            final CompletableFuture<Optional<? extends Content>> future = this.cache.load(key, remote, control)
                    .toCompletableFuture();

            // Clean up when complete - always remove from inflight map
            future.whenComplete((result, error) -> this.inflight.remove(key));

            return future;
        });
    }
}
