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

import com.auto1.pantera.asto.Key;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Global registry for the shared NegativeCache instance and per-repo legacy registrations.
 *
 * <p>Starting from v2.2.0 (WI-06), a single {@link NegativeCache} bean is shared across
 * all scopes (hosted, proxy, group). The {@link #setSharedCache(NegativeCache)} method
 * is called once at startup from {@code RepositorySlices}; adapters obtain the shared
 * bean via {@link #sharedCache()}.
 *
 * <p>The legacy per-repo {@link #register} / {@link #invalidateGlobally} API is retained
 * for backward compatibility with callers that have not been migrated.
 *
 * @since 1.20.13
 */
public final class NegativeCacheRegistry {

    /**
     * Singleton instance.
     */
    private static final NegativeCacheRegistry INSTANCE = new NegativeCacheRegistry();

    /**
     * Fallback instance used before the shared cache is initialized.
     * Created once at class-load time via a static factory method.
     */
    private static final NegativeCache FALLBACK = createFallback();

    /**
     * The single shared NegativeCache instance (set at startup).
     */
    private volatile NegativeCache shared;

    /**
     * Legacy per-repo caches: key = "repoType:repoName".
     */
    private final ConcurrentMap<String, NegativeCache> caches;

    /**
     * Private ctor.
     */
    private NegativeCacheRegistry() {
        this.caches = new ConcurrentHashMap<>();
    }

    /**
     * Get singleton instance.
     * @return Registry instance
     */
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
     * Check whether a shared cache has been explicitly set via
     * {@link #setSharedCache(NegativeCache)}.
     * @return true if the shared cache is initialized
     */
    public boolean isSharedCacheSet() {
        return this.shared != null;
    }

    /**
     * Get the shared NegativeCache bean.
     * Falls back to a default instance if not initialized.
     * @return Shared NegativeCache
     */
    public NegativeCache sharedCache() {
        final NegativeCache s = this.shared;
        if (s != null) {
            return s;
        }
        // Fallback for tests or early startup
        return FALLBACK;
    }

    /**
     * Register a negative cache instance (legacy API).
     * @param repoType Repository type
     * @param repoName Repository name
     * @param cache Negative cache instance
     */
    public void register(
        final String repoType, final String repoName, final NegativeCache cache
    ) {
        this.caches.put(key(repoType, repoName), cache);
    }

    /**
     * Unregister a negative cache instance.
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void unregister(final String repoType, final String repoName) {
        this.caches.remove(key(repoType, repoName));
    }

    /**
     * Invalidate a specific artifact path across ALL registered negative caches
     * and the shared instance.
     *
     * @param artifactPath Artifact path to invalidate
     */
    public void invalidateGlobally(final String artifactPath) {
        final Key artKey = new Key.From(artifactPath);
        if (this.shared != null) {
            this.shared.invalidate(artKey);
        }
        this.caches.values().forEach(cache -> cache.invalidate(artKey));
    }

    /**
     * Invalidate a specific artifact path in a specific repository's negative cache.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactPath Artifact path to invalidate
     */
    public void invalidate(
        final String repoType, final String repoName, final String artifactPath
    ) {
        final NegativeCache cache = this.caches.get(key(repoType, repoName));
        if (cache != null) {
            cache.invalidate(new Key.From(artifactPath));
        }
        // Also invalidate in the shared instance
        if (this.shared != null) {
            this.shared.invalidate(new Key.From(artifactPath));
        }
    }

    /**
     * Get the number of registered caches (legacy).
     * @return Count of registered caches
     */
    public int size() {
        return this.caches.size();
    }

    /**
     * Clear all registrations and the shared reference (for testing).
     */
    public void clear() {
        this.caches.clear();
        this.shared = null;
    }

    private static String key(final String repoType, final String repoName) {
        return repoType + ":" + repoName;
    }

    private static NegativeCache createFallback() {
        return new NegativeCache(new com.auto1.pantera.cache.NegativeCacheConfig());
    }
}
