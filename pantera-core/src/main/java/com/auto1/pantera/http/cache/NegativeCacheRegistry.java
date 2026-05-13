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
 * Global accessor for the single shared {@link NegativeCache} bean.
 *
 * <p>Set once at startup from {@code RepositorySlices}; adapters obtain it via
 * {@link #sharedCache()}. Falls back to a default instance if the shared cache
 * has not been initialized (used by tests and early startup).
 *
 * @since 1.20.13
 */
public final class NegativeCacheRegistry {

    private static final NegativeCacheRegistry INSTANCE = new NegativeCacheRegistry();

    private static final NegativeCache FALLBACK = createFallback();

    private volatile NegativeCache shared;

    private NegativeCacheRegistry() {
    }

    public static NegativeCacheRegistry instance() {
        return INSTANCE;
    }

    /**
     * Set the single shared NegativeCache bean. Called once at startup.
     * @param cache Shared NegativeCache instance
     */
    public void setSharedCache(final NegativeCache cache) {
        this.shared = cache;
    }

    /**
     * Check whether a shared cache has been explicitly set.
     * @return true if the shared cache is initialized
     */
    public boolean isSharedCacheSet() {
        return this.shared != null;
    }

    /**
     * Get the shared NegativeCache bean. Returns a default fallback if the
     * shared bean has not been initialized yet (tests, early startup).
     * @return Shared NegativeCache
     */
    public NegativeCache sharedCache() {
        final NegativeCache s = this.shared;
        if (s != null) {
            return s;
        }
        return FALLBACK;
    }

    /**
     * Clear the shared reference (for testing).
     */
    public void clear() {
        this.shared = null;
    }

    private static NegativeCache createFallback() {
        return new NegativeCache(new com.auto1.pantera.cache.NegativeCacheConfig());
    }
}
