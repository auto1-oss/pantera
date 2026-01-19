/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Optional;

/**
 * Proxy cache configuration parser for YAML.
 * Parses cache settings for negative caching and metadata caching.
 *
 * <p>Example YAML:
 * <pre>
 * cache:
 *   negative:
 *     enabled: true
 *     ttl: PT24H        # ISO-8601 duration (24 hours)
 *     maxSize: 50000    # Maximum entries
 *   metadata:
 *     enabled: true
 *     ttl: PT168H       # 7 days
 * </pre>
 *
 * @since 1.0
 */
public final class ProxyCacheConfig {

    /**
     * Default negative cache TTL (24 hours).
     */
    public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofHours(24);

    /**
     * Default negative cache max size (50,000 entries ~7.5MB).
     */
    public static final int DEFAULT_NEGATIVE_MAX_SIZE = 50_000;

    /**
     * Default metadata cache TTL (7 days).
     */
    public static final Duration DEFAULT_METADATA_TTL = Duration.ofDays(7);

    /**
     * YAML configuration.
     */
    private final YamlMapping yaml;

    /**
     * Ctor.
     * @param yaml YAML configuration mapping
     */
    public ProxyCacheConfig(final YamlMapping yaml) {
        this.yaml = yaml;
    }

    /**
     * Check if negative caching is enabled.
     * @return True if enabled (default: true)
     */
    public boolean negativeCacheEnabled() {
        return this.boolValue("cache", "negative", "enabled").orElse(true);
    }

    /**
     * Get negative cache TTL.
     * @return TTL duration (default: 24 hours)
     */
    public Duration negativeCacheTtl() {
        return this.durationValue("cache", "negative", "ttl")
            .orElse(DEFAULT_NEGATIVE_TTL);
    }

    /**
     * Get negative cache max size.
     * @return Max entries (default: 50,000)
     */
    public int negativeCacheMaxSize() {
        return this.intValue("cache", "negative", "maxSize")
            .orElse(DEFAULT_NEGATIVE_MAX_SIZE);
    }

    /**
     * Check if metadata caching is enabled.
     * @return True if enabled (default: false - needs implementation)
     */
    public boolean metadataCacheEnabled() {
        return this.boolValue("cache", "metadata", "enabled").orElse(false);
    }

    /**
     * Get metadata cache TTL.
     * @return TTL duration (default: 7 days)
     */
    public Duration metadataCacheTtl() {
        return this.durationValue("cache", "metadata", "ttl")
            .orElse(DEFAULT_METADATA_TTL);
    }

    /**
     * Check if any caching is configured.
     * @return True if cache section exists
     */
    public boolean hasCacheConfig() {
        return this.yaml.yamlMapping("cache") != null;
    }

    /**
     * Get boolean value from nested YAML path.
     * @param path YAML path segments
     * @return Optional boolean value
     */
    private Optional<Boolean> boolValue(final String... path) {
        YamlMapping current = this.yaml;
        for (int i = 0; i < path.length - 1; i++) {
            current = current.yamlMapping(path[i]);
            if (current == null) {
                return Optional.empty();
            }
        }
        final String value = current.string(path[path.length - 1]);
        return value == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(value));
    }

    /**
     * Get integer value from nested YAML path.
     * @param path YAML path segments
     * @return Optional integer value
     */
    private Optional<Integer> intValue(final String... path) {
        YamlMapping current = this.yaml;
        for (int i = 0; i < path.length - 1; i++) {
            current = current.yamlMapping(path[i]);
            if (current == null) {
                return Optional.empty();
            }
        }
        final String value = current.string(path[path.length - 1]);
        try {
            return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
        } catch (final NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Get duration value from nested YAML path.
     * Supports ISO-8601 duration format (e.g., PT24H, P1D).
     * @param path YAML path segments
     * @return Optional duration value
     */
    private Optional<Duration> durationValue(final String... path) {
        YamlMapping current = this.yaml;
        for (int i = 0; i < path.length - 1; i++) {
            current = current.yamlMapping(path[i]);
            if (current == null) {
                return Optional.empty();
            }
        }
        final String value = current.string(path[path.length - 1]);
        try {
            return value == null ? Optional.empty() : Optional.of(Duration.parse(value));
        } catch (final Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Create default configuration (all caching enabled with defaults).
     * @return Default configuration
     */
    public static ProxyCacheConfig defaults() {
        return new ProxyCacheConfig(com.amihaiemil.eoyaml.Yaml.createYamlMappingBuilder().build());
    }
}
