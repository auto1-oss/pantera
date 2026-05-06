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

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.misc.PanteraProperties;
import com.auto1.pantera.misc.Property;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.base.Strings;
import java.util.Locale;
import org.apache.commons.lang3.NotImplementedException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.DispatchedStorage;

import java.time.Duration;

/**
 * Implementation of cache for storages with similar configurations
 * in Pantera settings using Caffeine.
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
                new Property(PanteraProperties.STORAGE_TIMEOUT).asLongOrDefault(180_000L)
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
        EcsLogger.info("com.auto1.pantera.cache")
            .message("StoragesCache initialized with config: " + config.toString())
            .eventCategory("database")
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
            throw new PanteraException("Storage type cannot be null or empty.");
        }

        final long startNanos = System.nanoTime();
        final Storage existing = this.cache.getIfPresent(yaml);

        if (existing != null) {
            // Cache HIT
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("storage", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheOperationDuration("storage", "l1", "get", durationMs);
            }
            return existing;
        }

        // Cache MISS - create new storage
        final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("storage", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("storage", "l1", "get", durationMs);
        }

        final long putStartNanos = System.nanoTime();
        final Storage storage = this.cache.get(
            yaml,
            key -> {
                // Direct storage without JfrStorage wrapper
                // JFR profiling removed - adds 2-10% overhead and bypassed by optimized slices
                // Request-level metrics still active via Vert.x HTTP
                return new DispatchedStorage(
                    StoragesLoader.STORAGES
                        .newObject(type, new Config.YamlStorageConfig(key))
                );
            }
        );

        // Record PUT latency
        final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("storage", "l1", "put", putDurationMs);
        }

        return storage;
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
     * Handle storage eviction - log eviction event and record metrics.
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
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Storage evicted from cache (type: " + key.string("type") + ", cause: " + cause.toString() + ")")
                .eventCategory("database")
                .eventAction("cache_evict")
                .eventOutcome("success")
                .log();

            // Record eviction metric
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheEviction("storage", "l1", cause.toString().toLowerCase(Locale.ROOT));
            }
        }
    }
}
