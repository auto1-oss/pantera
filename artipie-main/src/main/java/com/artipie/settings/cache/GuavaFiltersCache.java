/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.ArtipieException;
import com.artipie.http.filter.Filters;
import com.artipie.misc.ArtipieProperties;
import com.artipie.misc.Property;
import com.artipie.cache.CacheConfig;
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
                new Property(ArtipieProperties.FILTERS_TIMEOUT).asLongOrDefault(180_000L)
            ))
            .recordStats()
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
            .build();
    }

    @Override
    public Optional<Filters> filters(final String reponame,
        final YamlMapping repoyaml) {
        return this.cache.get(
            reponame,
            key -> Optional.ofNullable(repoyaml.yamlMapping("filters")).map(Filters::new)
        );
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
}
