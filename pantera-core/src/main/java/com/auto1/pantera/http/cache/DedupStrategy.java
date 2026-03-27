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
