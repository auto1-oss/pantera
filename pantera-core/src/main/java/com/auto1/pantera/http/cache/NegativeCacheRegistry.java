/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

import com.artipie.asto.Key;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Global registry of all proxy NegativeCache instances.
 * Enables cross-adapter cache invalidation when artifacts are published.
 *
 * @since 1.20.13
 */
public final class NegativeCacheRegistry {

    /**
     * Singleton instance.
     */
    private static final NegativeCacheRegistry INSTANCE = new NegativeCacheRegistry();

    /**
     * Registered caches: key = "repoType:repoName".
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
     * Register a negative cache instance.
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
     * Invalidate a specific artifact path across ALL registered negative caches.
     * Called when an artifact is published to ensure stale 404 entries are cleared.
     *
     * @param artifactPath Artifact path to invalidate
     */
    public void invalidateGlobally(final String artifactPath) {
        final Key artKey = new Key.From(artifactPath);
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
    }

    /**
     * Get the number of registered caches.
     * @return Count of registered caches
     */
    public int size() {
        return this.caches.size();
    }

    /**
     * Clear all registrations (for testing).
     */
    public void clear() {
        this.caches.clear();
    }

    private static String key(final String repoType, final String repoName) {
        return repoType + ":" + repoName;
    }
}
