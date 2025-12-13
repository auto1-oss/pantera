/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Optional;

/**
 * Unified configuration for all negative caches (404 caching).
 * All negative caches serve the same purpose so they share configuration.
 * 
 * <p>Example YAML configuration in _server.yaml:
 * <pre>
 * caches:
 *   negative:
 *     ttl: 24h
 *     maxSize: 50000
 *     valkey:
 *       enabled: true
 *       l1MaxSize: 5000
 *       l1Ttl: 5m
 *       l2MaxSize: 5000000
 *       l2Ttl: 7d
 * </pre>
 * 
 * @since 1.18.22
 */
public final class NegativeCacheConfig {

    /**
     * Default TTL for negative cache (24 hours).
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Default maximum L1 cache size.
     */
    public static final int DEFAULT_MAX_SIZE = 50_000;

    /**
     * Default L1 TTL when L2 is enabled (5 minutes).
     */
    public static final Duration DEFAULT_L1_TTL = Duration.ofMinutes(5);

    /**
     * Default L2 TTL (7 days).
     */
    public static final Duration DEFAULT_L2_TTL = Duration.ofDays(7);

    /**
     * Default L1 max size when L2 enabled.
     */
    public static final int DEFAULT_L1_MAX_SIZE = 5_000;

    /**
     * Default L2 max size.
     */
    public static final int DEFAULT_L2_MAX_SIZE = 5_000_000;

    /**
     * Global instance (singleton).
     */
    private static volatile NegativeCacheConfig instance;

    /**
     * TTL for single-tier or fallback.
     */
    private final Duration ttl;

    /**
     * Max size for single-tier.
     */
    private final int maxSize;

    /**
     * Whether Valkey L2 is enabled.
     */
    private final boolean valkeyEnabled;

    /**
     * L1 cache max size.
     */
    private final int l1MaxSize;

    /**
     * L1 cache TTL.
     */
    private final Duration l1Ttl;

    /**
     * L2 cache max size (for documentation, Valkey handles this).
     */
    private final int l2MaxSize;

    /**
     * L2 cache TTL.
     */
    private final Duration l2Ttl;

    /**
     * Default constructor with sensible defaults.
     */
    public NegativeCacheConfig() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, false, 
             DEFAULT_L1_MAX_SIZE, DEFAULT_L1_TTL, 
             DEFAULT_L2_MAX_SIZE, DEFAULT_L2_TTL);
    }

    /**
     * Full constructor.
     * @param ttl Default TTL
     * @param maxSize Default max size
     * @param valkeyEnabled Whether L2 is enabled
     * @param l1MaxSize L1 max size
     * @param l1Ttl L1 TTL
     * @param l2MaxSize L2 max size
     * @param l2Ttl L2 TTL
     */
    public NegativeCacheConfig(
        final Duration ttl,
        final int maxSize,
        final boolean valkeyEnabled,
        final int l1MaxSize,
        final Duration l1Ttl,
        final int l2MaxSize,
        final Duration l2Ttl
    ) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.valkeyEnabled = valkeyEnabled;
        this.l1MaxSize = l1MaxSize;
        this.l1Ttl = l1Ttl;
        this.l2MaxSize = l2MaxSize;
        this.l2Ttl = l2Ttl;
    }

    /**
     * Get default TTL.
     * @return TTL duration
     */
    public Duration ttl() {
        return this.ttl;
    }

    /**
     * Get max size for single-tier cache.
     * @return Max size
     */
    public int maxSize() {
        return this.maxSize;
    }

    /**
     * Check if Valkey L2 is enabled.
     * @return True if two-tier caching is enabled
     */
    public boolean isValkeyEnabled() {
        return this.valkeyEnabled;
    }

    /**
     * Get L1 max size.
     * @return L1 max size
     */
    public int l1MaxSize() {
        return this.l1MaxSize;
    }

    /**
     * Get L1 TTL.
     * @return L1 TTL
     */
    public Duration l1Ttl() {
        return this.l1Ttl;
    }

    /**
     * Get L2 max size.
     * @return L2 max size
     */
    public int l2MaxSize() {
        return this.l2MaxSize;
    }

    /**
     * Get L2 TTL.
     * @return L2 TTL
     */
    public Duration l2Ttl() {
        return this.l2Ttl;
    }

    /**
     * Initialize global instance from YAML.
     * Should be called once at startup.
     * @param caches The caches YAML mapping from _server.yaml
     */
    public static void initialize(final YamlMapping caches) {
        if (instance == null) {
            synchronized (NegativeCacheConfig.class) {
                if (instance == null) {
                    instance = fromYaml(caches);
                }
            }
        }
    }

    /**
     * Get the global instance.
     * @return Global config (defaults if not initialized)
     */
    public static NegativeCacheConfig getInstance() {
        if (instance == null) {
            return new NegativeCacheConfig();
        }
        return instance;
    }

    /**
     * Reset for testing.
     */
    public static void reset() {
        instance = null;
    }

    /**
     * Parse configuration from YAML.
     * @param caches The caches YAML mapping
     * @return Parsed config
     */
    public static NegativeCacheConfig fromYaml(final YamlMapping caches) {
        if (caches == null) {
            return new NegativeCacheConfig();
        }
        final YamlMapping negative = caches.yamlMapping("negative");
        if (negative == null) {
            return new NegativeCacheConfig();
        }

        final Duration ttl = parseDuration(negative.string("ttl"), DEFAULT_TTL);
        final int maxSize = parseInt(negative.string("maxSize"), DEFAULT_MAX_SIZE);

        // Check for valkey sub-config
        final YamlMapping valkey = negative.yamlMapping("valkey");
        if (valkey != null && "true".equalsIgnoreCase(valkey.string("enabled"))) {
            return new NegativeCacheConfig(
                ttl,
                maxSize,
                true,
                parseInt(valkey.string("l1MaxSize"), DEFAULT_L1_MAX_SIZE),
                parseDuration(valkey.string("l1Ttl"), DEFAULT_L1_TTL),
                parseInt(valkey.string("l2MaxSize"), DEFAULT_L2_MAX_SIZE),
                parseDuration(valkey.string("l2Ttl"), DEFAULT_L2_TTL)
            );
        }

        return new NegativeCacheConfig(ttl, maxSize, false,
            DEFAULT_L1_MAX_SIZE, DEFAULT_L1_TTL, DEFAULT_L2_MAX_SIZE, DEFAULT_L2_TTL);
    }

    /**
     * Parse duration string (e.g., "24h", "7d", "5m").
     * @param value Duration string
     * @param defaultVal Default value
     * @return Parsed duration
     */
    private static Duration parseDuration(final String value, final Duration defaultVal) {
        if (value == null || value.isEmpty()) {
            return defaultVal;
        }
        try {
            final String trimmed = value.trim().toLowerCase();
            if (trimmed.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            } else if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (final NumberFormatException ex) {
            return defaultVal;
        }
    }

    /**
     * Parse integer string.
     * @param value Integer string
     * @param defaultVal Default value
     * @return Parsed integer
     */
    private static int parseInt(final String value, final int defaultVal) {
        if (value == null || value.isEmpty()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException ex) {
            return defaultVal;
        }
    }

    @Override
    public String toString() {
        return String.format(
            "NegativeCacheConfig{ttl=%s, maxSize=%d, valkeyEnabled=%s, l1MaxSize=%d, l1Ttl=%s, l2MaxSize=%d, l2Ttl=%s}",
            this.ttl, this.maxSize, this.valkeyEnabled, this.l1MaxSize, this.l1Ttl, this.l2MaxSize, this.l2Ttl
        );
    }
}

