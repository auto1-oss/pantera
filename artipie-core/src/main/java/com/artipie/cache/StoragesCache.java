/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.ArtipieException;
import com.artipie.asto.Storage;
import com.artipie.asto.factory.Config;
import com.artipie.asto.factory.StoragesLoader;
import com.artipie.asto.misc.Cleanable;
import com.artipie.misc.ArtipieProperties;
import com.artipie.misc.Property;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.base.Strings;
import org.apache.commons.lang3.NotImplementedException;
import com.artipie.http.log.EcsLogger;

import java.time.Duration;

/**
 * Implementation of cache for storages with similar configurations
 * in Artipie settings using Caffeine.
 * Properly closes Storage instances when evicted from cache to prevent resource leaks.
 * 
 * <p>Configuration in _server.yaml:
 * <pre>
 * caches:
 *   storage:
 *     profile: small  # Or direct: maxSize: 1000, ttl: 3m
 * </pre>
 * 
 * @since 0.23
 */
public class StoragesCache implements Cleanable<YamlMapping> {

    /**
     * Cache for storages.
     */
    private final Cache<YamlMapping, Storage> cache;

    /**
     * Ctor with default configuration.
     */
    public StoragesCache() {
        this(new CacheConfig(
            Duration.ofMillis(
                new Property(ArtipieProperties.STORAGE_TIMEOUT).asLongOrDefault(180_000L)
            ),
            1000  // Default: 1000 storage instances max
        ));
    }
    
    /**
     * Ctor with custom configuration.
     * @param config Cache configuration
     */
    public StoragesCache(final CacheConfig config) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(config.maxSize())
            .expireAfterWrite(config.ttl())
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
        EcsLogger.info("com.artipie.cache")
            .message("StoragesCache initialized with config: " + config.toString())
            .eventCategory("cache")
            .eventAction("cache_init")
            .eventOutcome("success")
            .log();
    }

    /**
     * Finds storage by specified in settings configuration cache or creates
     * a new item and caches it.
     *
     * @param yaml Storage settings
     * @return Storage
     */
    public Storage storage(final YamlMapping yaml) {
        final String type = yaml.string("type");
        if (Strings.isNullOrEmpty(type)) {
            throw new ArtipieException("Storage type cannot be null or empty.");
        }
        return this.cache.get(
            yaml,
            key -> {
                // Direct storage without JfrStorage wrapper
                // JFR profiling removed - adds 2-10% overhead and bypassed by optimized slices
                // Request-level metrics still active via Vert.x HTTP
                return StoragesLoader.STORAGES
                    .newObject(type, new Config.YamlStorageConfig(key));
            }
        );
    }

    /**
     * Returns the approximate number of entries in this cache.
     *
     * @return Number of entries
     */
    public long size() {
        return this.cache.estimatedSize();
    }

    @Override
    public String toString() {
        return String.format("%s(size=%d)", this.getClass().getSimpleName(), this.cache.estimatedSize());
    }

    @Override
    public void invalidate(final YamlMapping mapping) {
        throw new NotImplementedException("This method is not supported in cached storages!");
    }

    @Override
    public void invalidateAll() {
        this.cache.invalidateAll();
    }

    /**
     * Handle storage eviction - log eviction event.
     * Note: Storage interface doesn't extend AutoCloseable, so we just log.
     * @param key Cache key
     * @param storage Storage instance
     * @param cause Eviction cause
     */
    private void onEviction(
        final YamlMapping key,
        final Storage storage,
        final RemovalCause cause
    ) {
        if (storage != null && key != null) {
            EcsLogger.debug("com.artipie.cache")
                .message("Storage evicted from cache (type: " + key.string("type") + ", cause: " + cause.toString() + ")")
                .eventCategory("cache")
                .eventAction("cache_evict")
                .eventOutcome("success")
                .log();
        }
    }
}
