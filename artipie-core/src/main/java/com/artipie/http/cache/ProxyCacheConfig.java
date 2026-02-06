/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Unified proxy cache configuration parsed from YAML.
 * Controls negative caching, metadata caching, cooldown toggle, request deduplication,
 * conditional requests (ETag), stale-while-revalidate, retry, and metrics.
 *
 * <p>Example YAML:
 * <pre>
 * cache:
 *   negative:
 *     enabled: true
 *     ttl: PT24H
 *     maxSize: 50000
 *   metadata:
 *     enabled: true
 *     ttl: PT168H
 *   cooldown:
 *     enabled: true
 *   dedup_strategy: signal          # none | storage | signal
 *   conditional_requests: true      # ETag / If-None-Match
 *   stale_while_revalidate:
 *     enabled: false
 *     max_age: PT1H
 *   retry:
 *     max_retries: 2
 *     initial_delay: PT0.1S
 *     backoff_multiplier: 2.0
 *   metrics: true
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
     * Default stale-while-revalidate max age (1 hour).
     */
    public static final Duration DEFAULT_STALE_MAX_AGE = Duration.ofHours(1);

    /**
     * Default retry initial delay (100ms).
     */
    public static final Duration DEFAULT_RETRY_INITIAL_DELAY = Duration.ofMillis(100);

    /**
     * Default retry backoff multiplier.
     */
    public static final double DEFAULT_RETRY_BACKOFF_MULTIPLIER = 2.0;

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
     * @return True if enabled (default: false)
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
     * Check if cooldown is enabled for this adapter.
     * @return True if enabled (default: false)
     */
    public boolean cooldownEnabled() {
        return this.boolValue("cache", "cooldown", "enabled").orElse(false);
    }

    /**
     * Get request deduplication strategy.
     * @return Dedup strategy (default: SIGNAL)
     */
    public DedupStrategy dedupStrategy() {
        return this.stringValue("cache", "dedup_strategy")
            .map(s -> DedupStrategy.valueOf(s.toUpperCase(Locale.ROOT)))
            .orElse(DedupStrategy.SIGNAL);
    }

    /**
     * Check if conditional requests (ETag/If-None-Match) are enabled.
     * @return True if enabled (default: true)
     */
    public boolean conditionalRequestsEnabled() {
        return this.boolValue("cache", "conditional_requests").orElse(true);
    }

    /**
     * Check if stale-while-revalidate is enabled.
     * @return True if enabled (default: false)
     */
    public boolean staleWhileRevalidateEnabled() {
        return this.boolValue("cache", "stale_while_revalidate", "enabled")
            .orElse(false);
    }

    /**
     * Get stale-while-revalidate max age.
     * @return Max age duration (default: 1 hour)
     */
    public Duration staleMaxAge() {
        return this.durationValue("cache", "stale_while_revalidate", "max_age")
            .orElse(DEFAULT_STALE_MAX_AGE);
    }

    /**
     * Get maximum number of retry attempts for upstream requests.
     * @return Max retries (default: 0 = disabled)
     */
    public int retryMaxRetries() {
        return this.intValue("cache", "retry", "max_retries").orElse(0);
    }

    /**
     * Get initial delay between retry attempts.
     * @return Initial delay duration (default: 100ms)
     */
    public Duration retryInitialDelay() {
        return this.durationValue("cache", "retry", "initial_delay")
            .orElse(DEFAULT_RETRY_INITIAL_DELAY);
    }

    /**
     * Get backoff multiplier for retry delays.
     * @return Backoff multiplier (default: 2.0)
     */
    public double retryBackoffMultiplier() {
        return this.doubleValue("cache", "retry", "backoff_multiplier")
            .orElse(DEFAULT_RETRY_BACKOFF_MULTIPLIER);
    }

    /**
     * Check if proxy metrics recording is enabled.
     * @return True if enabled (default: true)
     */
    public boolean metricsEnabled() {
        return this.boolValue("cache", "metrics").orElse(true);
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
        final String value = this.rawValue(path);
        return value == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(value));
    }

    /**
     * Get integer value from nested YAML path.
     * @param path YAML path segments
     * @return Optional integer value
     */
    private Optional<Integer> intValue(final String... path) {
        final String value = this.rawValue(path);
        try {
            return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
        } catch (final NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Get double value from nested YAML path.
     * @param path YAML path segments
     * @return Optional double value
     */
    private Optional<Double> doubleValue(final String... path) {
        final String value = this.rawValue(path);
        try {
            return value == null ? Optional.empty() : Optional.of(Double.parseDouble(value));
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
        final String value = this.rawValue(path);
        try {
            return value == null ? Optional.empty() : Optional.of(Duration.parse(value));
        } catch (final Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Get string value from nested YAML path.
     * @param path YAML path segments
     * @return Optional string value
     */
    private Optional<String> stringValue(final String... path) {
        return Optional.ofNullable(this.rawValue(path));
    }

    /**
     * Navigate YAML path and return raw string value at leaf.
     * @param path YAML path segments
     * @return Raw string value or null
     */
    private String rawValue(final String... path) {
        YamlMapping current = this.yaml;
        for (int idx = 0; idx < path.length - 1; idx++) {
            current = current.yamlMapping(path[idx]);
            if (current == null) {
                return null;
            }
        }
        return current.string(path[path.length - 1]);
    }

    /**
     * Create default configuration (all caching enabled with defaults).
     * @return Default configuration
     */
    public static ProxyCacheConfig defaults() {
        return new ProxyCacheConfig(
            com.amihaiemil.eoyaml.Yaml.createYamlMappingBuilder().build()
        );
    }
}
