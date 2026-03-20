/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Global registry of cooldown inspectors for cache invalidation during unblock.
 * Allows unblock operations to invalidate inspector internal caches across all adapters.
 * 
 * @since 1.19
 */
public final class InspectorRegistry {

    /**
     * Singleton instance.
     */
    private static final InspectorRegistry INSTANCE = new InspectorRegistry();

    /**
     * Registry of invalidatable inspectors by repository type and name.
     * Key format: "{repoType}:{repoName}" (e.g., "docker:docker_proxy")
     */
    private final ConcurrentMap<String, InvalidatableInspector> inspectors;

    private InspectorRegistry() {
        this.inspectors = new ConcurrentHashMap<>();
    }

    /**
     * Get singleton instance.
     * 
     * @return Registry instance
     */
    public static InspectorRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register inspector for repository.
     * 
     * @param repoType Repository type (docker, npm, maven, etc.)
     * @param repoName Repository name
     * @param inspector Inspector instance
     */
    public void register(
        final String repoType,
        final String repoName,
        final InvalidatableInspector inspector
    ) {
        this.inspectors.put(key(repoType, repoName), inspector);
    }

    /**
     * Unregister inspector for repository.
     * 
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void unregister(final String repoType, final String repoName) {
        this.inspectors.remove(key(repoType, repoName));
    }

    /**
     * Get inspector for repository.
     * 
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Inspector if registered
     */
    public Optional<InvalidatableInspector> get(final String repoType, final String repoName) {
        return Optional.ofNullable(this.inspectors.get(key(repoType, repoName)));
    }

    /**
     * Invalidate specific artifact in repository inspector cache.
     * Called when artifact is manually unblocked.
     * 
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifact Artifact name
     * @param version Version
     */
    public void invalidate(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version
    ) {
        this.get(repoType, repoName).ifPresent(
            inspector -> inspector.invalidate(artifact, version)
        );
    }

    /**
     * Clear all cached data for repository inspector.
     * Called when all artifacts for a repository are unblocked.
     * 
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void clearAll(final String repoType, final String repoName) {
        this.get(repoType, repoName).ifPresent(InvalidatableInspector::clearAll);
    }

    private static String key(final String repoType, final String repoName) {
        return String.format("%s:%s", repoType, repoName);
    }

    /**
     * Interface for inspectors that support cache invalidation.
     * All inspectors with internal caches should implement this.
     */
    public interface InvalidatableInspector {
        /**
         * Invalidate cached data for specific artifact version.
         * 
         * @param artifact Artifact name
         * @param version Version
         */
        void invalidate(String artifact, String version);

        /**
         * Clear all cached data.
         */
        void clearAll();
    }
}
