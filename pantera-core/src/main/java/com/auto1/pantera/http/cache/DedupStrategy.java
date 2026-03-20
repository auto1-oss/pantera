/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

/**
 * Request deduplication strategy for proxy caches.
 *
 * @since 1.20.13
 */
public enum DedupStrategy {

    /**
     * No deduplication. Each concurrent request independently fetches from upstream.
     */
    NONE,

    /**
     * Storage-level deduplication. Uses storage key locking to prevent
     * concurrent writes to the same cache key. Second request waits for
     * the first to complete and reads from cache.
     */
    STORAGE,

    /**
     * Signal-based deduplication (zero-copy). First request fetches and caches,
     * then signals completion. Waiting requests read from cache on SUCCESS
     * signal, or return appropriate error on NOT_FOUND / ERROR signals.
     * No response body buffering in memory.
     */
    SIGNAL
}
