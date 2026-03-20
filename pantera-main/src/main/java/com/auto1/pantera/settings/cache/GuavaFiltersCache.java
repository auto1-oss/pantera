/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.filter.Filters;
import com.auto1.pantera.misc.PanteraProperties;
import com.auto1.pantera.misc.Property;
import com.auto1.pantera.cache.CacheConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;

/**
 * Implementation of cache for filters using Caffeine.
 * 
 * <p>Configuration in _server.yaml:
 * <pre>
 * caches:
 *   filters:
 *     profile: small  # Or direct: maxSize: 1000, ttl: 3m
 * </pre>
 *
 * @since 0.28
 */
public class GuavaFiltersCache implements FiltersCache {
    /**
     * Cache for filters.
     */
    private final Cache<String, Optional<Filters>> cache;

    /**
     * Ctor with default configuration.
     */
    public GuavaFiltersCache() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(1_000)  // Default: 1000 repos
            .expireAfterAccess(Duration.ofMillis(
                new Property(PanteraProperties.FILTERS_TIMEOUT).asLongOrDefault(180_000L)
            ))
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
    }
    
    /**
     * Ctor with configuration support.
     * @param serverYaml Server configuration YAML
     */
    public GuavaFiltersCache(final YamlMapping serverYaml) {
        final CacheConfig config = CacheConfig.from(serverYaml, "filters");
        this.cache = Caffeine.newBuilder()
            .maximumSize(config.maxSize())
            .expireAfterAccess(config.ttl())
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
    }

    @Override
    public Optional<Filters> filters(final String reponame,
        final YamlMapping repoyaml) {
        final long startNanos = System.nanoTime();
        final Optional<Filters> existing = this.cache.getIfPresent(reponame);

        if (existing != null) {
            // Cache HIT
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheHit("filters", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheOperationDuration("filters", "l1", "get", durationMs);
            }
            return existing;
        }

        // Cache MISS
        final long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance().recordCacheMiss("filters", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("filters", "l1", "get", durationMs);
        }

        final long putStartNanos = System.nanoTime();
        final Optional<Filters> result = this.cache.get(
            reponame,
            key -> Optional.ofNullable(repoyaml.yamlMapping("filters")).map(Filters::new)
        );

        // Record PUT latency
        final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("filters", "l1", "put", putDurationMs);
        }

        return result;
    }

    @Override
    public long size() {
        return this.cache.estimatedSize();
    }

    @Override
    public String toString() {
        return String.format(
            "%s(size=%d)",
            this.getClass().getSimpleName(), this.cache.estimatedSize()
        );
    }

    @Override
    public void invalidate(final String reponame) {
        this.cache.invalidate(reponame);
    }

    @Override
    public void invalidateAll() {
        this.cache.invalidateAll();
    }

    /**
     * Handle filter eviction - record metrics.
     * @param key Cache key (repository name)
     * @param filters Filters instance
     * @param cause Eviction cause
     */
    private void onEviction(
        final String key,
        final Optional<Filters> filters,
        final com.github.benmanes.caffeine.cache.RemovalCause cause
    ) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheEviction("filters", "l1", cause.toString().toLowerCase());
        }
    }
}
