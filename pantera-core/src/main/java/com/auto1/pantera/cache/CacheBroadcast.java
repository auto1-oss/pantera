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
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.misc.Cleanable;

/**
 * Minimal cross-instance cache-invalidation contract implemented by
 * {@link CacheInvalidationPubSub}.
 * <p>
 * Extracted so consumers that only need publish/register (revocation
 * broadcast, {@code PublishingCleanable}, {@code PublishingFiltersCache}, the
 * breaker/bulkhead settings-loader broadcast) can depend on this narrow
 * interface instead of the concrete Valkey-backed implementation — letting
 * tests substitute a real, deterministic in-process fake instead of a
 * Testcontainers Valkey instance for two-instance propagation tests.
 *
 * @since 2.3.0
 */
public interface CacheBroadcast {

    /**
     * Publish an invalidation message for a specific key. Other instances
     * registered for {@code cacheType} call {@code cache.invalidate(key)} on
     * receipt.
     * @param cacheType Cache type name
     * @param key Cache key to invalidate
     */
    void publish(String cacheType, String key);

    /**
     * Publish an invalidateAll message. Other instances registered for
     * {@code cacheType} call {@code cache.invalidateAll()} on receipt.
     * @param cacheType Cache type name
     */
    void publishAll(String cacheType);

    /**
     * Register a cache for remote invalidation.
     * @param name Cache type name (e.g. "auth", "filters", "policy", "revocation")
     * @param cache Cache instance to invalidate on remote messages
     */
    void register(String name, Cleanable<String> cache);
}
