/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.cache.GlobalCacheConfig;
import com.artipie.cache.ValkeyConnection;
import com.artipie.http.cache.NegativeCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry for group negative caches.
 * Provides static methods for cache management and REST API support.
 *
 * <p>This registry tracks all group negative cache instances and provides:
 * <ul>
 *   <li>Global cache invalidation (for publish events)</li>
 *   <li>Group-specific cache management</li>
 *   <li>REST API support for cache operations</li>
 * </ul>
 *
 * @since 1.21.0
 */
public final class GroupCacheRegistry {

    /**
     * Global registry of all group caches by group name.
     */
    private static final ConcurrentHashMap<String, GroupCacheEntry> INSTANCES =
        new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private GroupCacheRegistry() {
        // Utility class
    }

    /**
     * Register a group cache instance.
     *
     * @param groupName Group repository name
     * @param negativeCache Negative cache instance
     */
    public static void register(
        final String groupName,
        final NegativeCache negativeCache
    ) {
        INSTANCES.put(groupName, new GroupCacheEntry(negativeCache));
    }

    /**
     * Unregister a group cache instance.
     *
     * @param groupName Group repository name
     */
    public static void unregister(final String groupName) {
        INSTANCES.remove(groupName);
    }

    /**
     * Get a specific group's cache entry.
     *
     * @param groupName Group repository name
     * @return Optional cache entry
     */
    public static Optional<GroupCacheEntry> getInstance(final String groupName) {
        return Optional.ofNullable(INSTANCES.get(groupName));
    }

    /**
     * Get list of all registered group names.
     *
     * @return List of group names
     */
    public static List<String> registeredGroups() {
        return new ArrayList<>(INSTANCES.keySet());
    }

    /**
     * Clear all negative cache entries for a specific group.
     *
     * @param groupName Group repository name
     * @return Future completing when cleared
     */
    public static CompletableFuture<Void> clearGroup(final String groupName) {
        final GroupCacheEntry entry = INSTANCES.get(groupName);
        if (entry != null) {
            entry.negativeCache().clear();
        }
        // Also clear L2 if available
        return clearL2Only(groupName);
    }

    /**
     * Invalidate package in a specific group.
     *
     * @param groupName Group repository name
     * @param packagePath Package path
     * @return Future completing when invalidated
     */
    public static CompletableFuture<Void> invalidatePackageInGroup(
        final String groupName,
        final String packagePath
    ) {
        final GroupCacheEntry entry = INSTANCES.get(groupName);
        if (entry != null) {
            entry.negativeCache().invalidatePrefix(packagePath);
        }
        // Also invalidate L2 if available
        return invalidateL2Only(groupName, packagePath);
    }

    /**
     * Invalidate package in ALL groups globally.
     * Used when a package is published to ensure group repos can find it.
     *
     * @param packagePath Package path
     * @return Future completing when invalidated
     */
    public static CompletableFuture<Void> invalidatePackageGlobally(
        final String packagePath
    ) {
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        // Invalidate L1 in all instances
        for (final GroupCacheEntry entry : INSTANCES.values()) {
            entry.negativeCache().invalidatePrefix(packagePath);
        }
        // Invalidate L2 globally
        futures.add(invalidateGlobalL2Only(packagePath));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Clear L2 cache only for a group.
     *
     * @param groupName Group name
     * @return Future completing when cleared
     */
    private static CompletableFuture<Void> clearL2Only(final String groupName) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String pattern = "negative:group:" + groupName + ":*";
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async()
                        .del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }

    /**
     * Invalidate L2 cache only for a group/package.
     *
     * @param groupName Group name
     * @param packagePath Package path
     * @return Future completing when invalidated
     */
    private static CompletableFuture<Void> invalidateL2Only(
        final String groupName,
        final String packagePath
    ) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String pattern = "negative:group:" + groupName + ":*" + packagePath + "*";
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async()
                        .del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }

    /**
     * Invalidate L2 cache globally for a package.
     *
     * @param packagePath Package path
     * @return Future completing when invalidated
     */
    private static CompletableFuture<Void> invalidateGlobalL2Only(
        final String packagePath
    ) {
        final ValkeyConnection valkey = GlobalCacheConfig.valkeyConnection().orElse(null);
        if (valkey == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String pattern = "negative:group:*:*" + packagePath + "*";
        return valkey.async().keys(pattern)
            .thenCompose(keys -> {
                if (keys != null && !keys.isEmpty()) {
                    return valkey.async()
                        .del(keys.toArray(new String[0]))
                        .thenApply(count -> null);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toCompletableFuture()
            .thenApply(v -> null);
    }

    /**
     * Cache entry containing group cache components.
     *
     * @param negativeCache Negative cache instance
     */
    public record GroupCacheEntry(NegativeCache negativeCache) {
        /**
         * Get L1 cache size.
         * @return Estimated cache size
         */
        public long size() {
            return negativeCache.size();
        }

        /**
         * Check if two-tier caching is enabled.
         * @return True if L2 is enabled
         */
        public boolean isTwoTier() {
            // Check if valkey is available globally
            return GlobalCacheConfig.valkeyConnection().isPresent();
        }
    }
}
