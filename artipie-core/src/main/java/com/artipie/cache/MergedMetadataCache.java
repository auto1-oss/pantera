/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-tier cache for storing merged metadata from group members.
 * L1: Caffeine in-memory cache (hot data, fast access).
 * L2: Valkey/Redis (optional, warm data, shared across instances).
 *
 * <p>Cache key format: meta:{groupName}:{adapterType}:{packageName}
 *
 * <p>Supports adapter types: npm, go, pypi, maven, docker, composer, gradle.
 *
 * <p>Non-blocking design for Vert.x event loop compatibility.
 *
 * @since 1.18.0
 */
public final class MergedMetadataCache {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.artipie.cache";

    /**
     * L2 timeout in milliseconds.
     */
    private static final long L2_TIMEOUT_MS = 100L;

    /**
     * Supported adapter types.
     */
    private static final List<String> ADAPTER_TYPES = Arrays.asList(
        "npm", "go", "pypi", "maven", "docker", "composer", "gradle"
    );

    /**
     * Group repository name.
     */
    private final String grpName;

    /**
     * Group settings for TTL configuration.
     */
    private final GroupSettings grpSettings;

    /**
     * L1 cache (in-memory, hot data).
     * Key format: adapterType:packageName
     */
    private final Cache<String, byte[]> l1Cache;

    /**
     * L2 cache connection (optional).
     */
    private final Optional<ValkeyConnection> valkeyConn;

    /**
     * Create merged metadata cache.
     *
     * @param groupName Group repository name
     * @param settings Group settings for TTL configuration
     * @param valkey Optional Valkey connection for L2 cache
     */
    public MergedMetadataCache(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.grpName = Objects.requireNonNull(
            groupName, "groupName cannot be null"
        );
        this.grpSettings = Objects.requireNonNull(
            settings, "settings cannot be null"
        );
        this.valkeyConn = Objects.requireNonNull(
            valkey, "valkey cannot be null"
        );
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(settings.cacheSizing().l1MaxEntries())
            .expireAfterWrite(computeL1Ttl(settings), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        EcsLogger.debug(LOGGER)
            .message(String.format("MergedMetadataCache created (l1_max_entries=%d, l2_enabled=%b)", settings.cacheSizing().l1MaxEntries(), valkey.isPresent()))
            .eventCategory("cache")
            .eventAction("metadata_cache_create")
            .field("repository.name", groupName)
            .log();
    }

    /**
     * Get merged metadata for a package and adapter type.
     *
     * @param adapterType Adapter type (npm, maven, pypi, etc.)
     * @param packageName Package name
     * @return Future with optional metadata bytes
     */
    public CompletableFuture<Optional<byte[]>> get(
        final String adapterType,
        final String packageName
    ) {
        Objects.requireNonNull(adapterType, "adapterType cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        final String l1Key = this.l1Key(adapterType, packageName);
        // Check L1 first
        final byte[] l1Result = this.l1Cache.getIfPresent(l1Key);
        if (l1Result != null) {
            EcsLogger.trace(LOGGER)
                .message("L1 cache hit")
                .eventCategory("cache")
                .eventAction("metadata_get")
                .eventOutcome("success")
                .field("repository.name", this.grpName)
                .field("repository.type", adapterType)
                .field("package.name", packageName)
                .log();
            return CompletableFuture.completedFuture(
                Optional.of(Arrays.copyOf(l1Result, l1Result.length))
            );
        }
        // Check L2 if available
        if (this.valkeyConn.isPresent()) {
            return this.getFromL2(adapterType, packageName)
                .thenApply(l2Result -> {
                    if (l2Result.isPresent()) {
                        final byte[] data = l2Result.get();
                        // Promote copy to L1
                        this.l1Cache.put(l1Key, Arrays.copyOf(data, data.length));
                        EcsLogger.trace(LOGGER)
                            .message("L2 cache hit, promoted to L1")
                            .eventCategory("cache")
                            .eventAction("metadata_get")
                            .eventOutcome("success")
                            .field("repository.name", this.grpName)
                            .field("repository.type", adapterType)
                            .field("package.name", packageName)
                            .log();
                        // Return defensive copy to caller
                        return Optional.of(Arrays.copyOf(data, data.length));
                    }
                    return l2Result;
                });
        }
        // Cache miss
        EcsLogger.trace(LOGGER)
            .message("Cache miss")
            .eventCategory("cache")
            .eventAction("metadata_get")
            .eventOutcome("failure")
            .field("repository.name", this.grpName)
            .field("repository.type", adapterType)
            .field("package.name", packageName)
            .log();
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Store merged metadata for a package and adapter type.
     *
     * @param adapterType Adapter type (npm, maven, pypi, etc.)
     * @param packageName Package name
     * @param mergedMetadata Merged metadata bytes
     * @return Future completing when stored
     */
    public CompletableFuture<Void> put(
        final String adapterType,
        final String packageName,
        final byte[] mergedMetadata
    ) {
        Objects.requireNonNull(adapterType, "adapterType cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        Objects.requireNonNull(mergedMetadata, "mergedMetadata cannot be null");
        final String l1Key = this.l1Key(adapterType, packageName);
        // Store defensive copy in L1 to prevent external modification
        this.l1Cache.put(l1Key, Arrays.copyOf(mergedMetadata, mergedMetadata.length));
        EcsLogger.trace(LOGGER)
            .message("Metadata stored in L1")
            .eventCategory("cache")
            .eventAction("metadata_put")
            .eventOutcome("success")
            .field("repository.name", this.grpName)
            .field("repository.type", adapterType)
            .field("package.name", packageName)
            .field("file.size", mergedMetadata.length)
            .log();
        // Store in L2 if available
        if (this.valkeyConn.isPresent()) {
            return this.saveToL2(adapterType, packageName, mergedMetadata);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate all adapter types for a package.
     *
     * @param packageName Package name to invalidate
     * @return Future completing when invalidated
     */
    public CompletableFuture<Void> invalidate(final String packageName) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        EcsLogger.debug(LOGGER)
            .message("Invalidating all adapter types for package")
            .eventCategory("cache")
            .eventAction("metadata_invalidate")
            .field("repository.name", this.grpName)
            .field("package.name", packageName)
            .log();
        // Invalidate L1 for all adapter types
        for (final String adapter : ADAPTER_TYPES) {
            final String l1Key = this.l1Key(adapter, packageName);
            this.l1Cache.invalidate(l1Key);
        }
        // Invalidate L2 if available
        if (this.valkeyConn.isPresent()) {
            final CompletableFuture<?>[] futures = ADAPTER_TYPES.stream()
                .map(adapter -> this.invalidateL2(adapter, packageName))
                .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate specific adapter type for a package.
     *
     * @param adapterType Adapter type
     * @param packageName Package name to invalidate
     * @return Future completing when invalidated
     */
    public CompletableFuture<Void> invalidate(
        final String adapterType,
        final String packageName
    ) {
        Objects.requireNonNull(adapterType, "adapterType cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        final String l1Key = this.l1Key(adapterType, packageName);
        // Invalidate L1
        this.l1Cache.invalidate(l1Key);
        EcsLogger.debug(LOGGER)
            .message("Invalidated metadata cache entry")
            .eventCategory("cache")
            .eventAction("metadata_invalidate")
            .field("repository.name", this.grpName)
            .field("repository.type", adapterType)
            .field("package.name", packageName)
            .log();
        // Invalidate L2 if available
        if (this.valkeyConn.isPresent()) {
            return this.invalidateL2(adapterType, packageName);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Get from L2 cache.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @return Future with optional metadata
     */
    private CompletableFuture<Optional<byte[]>> getFromL2(
        final String adapterType,
        final String packageName
    ) {
        final String key = this.l2Key(adapterType, packageName);
        return this.valkeyConn.get().async().get(key)
            .toCompletableFuture()
            .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .<Optional<byte[]>>thenApply(bytes -> {
                if (bytes == null) {
                    return Optional.empty();
                }
                return Optional.of(bytes);
            })
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("L2 get failed")
                    .eventCategory("cache")
                    .eventAction("metadata_l2_get")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("repository.type", adapterType)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return Optional.empty();
            });
    }

    /**
     * Save to L2 cache.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @param metadata Metadata to save
     * @return Future completing when saved
     */
    private CompletableFuture<Void> saveToL2(
        final String adapterType,
        final String packageName,
        final byte[] metadata
    ) {
        final String key = this.l2Key(adapterType, packageName);
        // Use metadata TTL from settings for L2 entries
        final long seconds =
            this.grpSettings.metadataSettings().ttl().getSeconds();
        return this.valkeyConn.get().async().setex(key, seconds, metadata)
            .toCompletableFuture()
            .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .thenApply(v -> (Void) null)
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("L2 save failed")
                    .eventCategory("cache")
                    .eventAction("metadata_l2_save")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("repository.type", adapterType)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Invalidate L2 cache entry.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @return Future completing when invalidated
     */
    private CompletableFuture<Void> invalidateL2(
        final String adapterType,
        final String packageName
    ) {
        final String key = this.l2Key(adapterType, packageName);
        return this.valkeyConn.get().async().del(key)
            .toCompletableFuture()
            .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .thenApply(v -> (Void) null)
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("L2 invalidate failed")
                    .eventCategory("cache")
                    .eventAction("metadata_l2_invalidate")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("repository.type", adapterType)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Compute L1 cache key.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @return L1 key
     */
    private String l1Key(final String adapterType, final String packageName) {
        return String.format("%s:%s", adapterType, packageName);
    }

    /**
     * Compute L2 cache key.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @return Redis key
     */
    private String l2Key(final String adapterType, final String packageName) {
        return String.format(
            "meta:%s:%s:%s", this.grpName, adapterType, packageName
        );
    }

    /**
     * Compute L1 TTL based on settings.
     *
     * @param settings Group settings
     * @return L1 TTL in milliseconds
     */
    private static long computeL1Ttl(final GroupSettings settings) {
        // Use metadata TTL from settings directly
        final Duration metadataTtl = settings.metadataSettings().ttl();
        return metadataTtl.toMillis();
    }

    @Override
    public String toString() {
        return String.format(
            "MergedMetadataCache{group=%s, l1Size=%d, l2=%s}",
            this.grpName,
            this.l1Cache.estimatedSize(),
            this.valkeyConn.isPresent() ? "enabled" : "disabled"
        );
    }
}
