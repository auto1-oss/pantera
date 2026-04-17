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
package com.auto1.pantera.cooldown.metadata;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;

/**
 * Configuration for FilteredMetadataCache (cooldown metadata caching).
 * 
 * <p>Example YAML configuration in pantera.yaml:
 * <pre>
 * meta:
 *   caches:
 *     cooldown-metadata:
 *       ttl: 24h
 *       maxSize: 5000
 *       valkey:
 *         enabled: true
 *         l1MaxSize: 500
 *         l1Ttl: 5m
 *         l2Ttl: 24h
 * </pre>
 * 
 * <p>For large NPM metadata (3-38MB per package), consider:
 * <ul>
 *   <li>Reducing l1MaxSize to avoid memory pressure</li>
 *   <li>Setting l1MaxSize to 0 for L2-only mode (Valkey only)</li>
 * </ul>
 * 
 * @since 1.18.22
 */
public final class FilteredMetadataCacheConfig {

    /**
     * Default TTL for cache entries (24 hours).
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Default maximum L1 cache size (50,000 packages — H4).
     * Configurable via {@code PANTERA_COOLDOWN_METADATA_L1_SIZE} env var.
     */
    public static final int DEFAULT_MAX_SIZE = 50_000;

    /**
     * Default L1 TTL when L2 is enabled (5 minutes).
     */
    public static final Duration DEFAULT_L1_TTL = Duration.ofMinutes(5);

    /**
     * Default L2 TTL (24 hours).
     */
    public static final Duration DEFAULT_L2_TTL = Duration.ofHours(24);

    /**
     * Default L1 max size when L2 enabled (500 - reduced for large metadata).
     */
    public static final int DEFAULT_L1_MAX_SIZE = 500;

    /**
     * Default L2 max size (not enforced by Valkey, informational only).
     */
    public static final int DEFAULT_L2_MAX_SIZE = 100_000;

    /**
     * Global instance (singleton).
     */
    private static volatile FilteredMetadataCacheConfig instance;

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
     * L2 cache TTL.
     */
    private final Duration l2Ttl;

    /**
     * L2 cache max size (informational, not enforced by Valkey).
     */
    private final int l2MaxSize;

    /**
     * Default constructor with sensible defaults.
     */
    public FilteredMetadataCacheConfig() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, false, 
             DEFAULT_L1_MAX_SIZE, DEFAULT_L1_TTL, DEFAULT_L2_TTL, DEFAULT_L2_MAX_SIZE);
    }

    /**
     * Full constructor.
     * @param ttl Default TTL
     * @param maxSize Default max size (single-tier)
     * @param valkeyEnabled Whether L2 is enabled
     * @param l1MaxSize L1 max size (0 for L2-only mode)
     * @param l1Ttl L1 TTL
     * @param l2Ttl L2 TTL
     * @param l2MaxSize L2 max size (informational)
     */
    public FilteredMetadataCacheConfig(
        final Duration ttl,
        final int maxSize,
        final boolean valkeyEnabled,
        final int l1MaxSize,
        final Duration l1Ttl,
        final Duration l2Ttl,
        final int l2MaxSize
    ) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.valkeyEnabled = valkeyEnabled;
        this.l1MaxSize = l1MaxSize;
        this.l1Ttl = l1Ttl;
        this.l2Ttl = l2Ttl;
        this.l2MaxSize = l2MaxSize;
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
     * A value of 0 indicates L2-only mode (no in-memory caching).
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
     * Get L2 TTL.
     * @return L2 TTL
     */
    public Duration l2Ttl() {
        return this.l2Ttl;
    }

    /**
     * Get L2 max size.
     * @return L2 max size
     */
    public int l2MaxSize() {
        return this.l2MaxSize;
    }

    /**
     * Check if L2-only mode is enabled (l1MaxSize == 0 and valkey enabled).
     * @return True if L2-only mode
     */
    public boolean isL2OnlyMode() {
        return this.valkeyEnabled && this.l1MaxSize == 0;
    }

    /**
     * Initialize global instance from YAML.
     * Should be called once at startup.
     * @param caches The caches YAML mapping from pantera.yaml
     */
    public static void initialize(final YamlMapping caches) {
        if (instance == null) {
            synchronized (FilteredMetadataCacheConfig.class) {
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
    public static FilteredMetadataCacheConfig getInstance() {
        if (instance == null) {
            return new FilteredMetadataCacheConfig();
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
    public static FilteredMetadataCacheConfig fromYaml(final YamlMapping caches) {
        if (caches == null) {
            return new FilteredMetadataCacheConfig();
        }
        final YamlMapping cooldownMetadata = caches.yamlMapping("cooldown-metadata");
        if (cooldownMetadata == null) {
            return new FilteredMetadataCacheConfig();
        }

        final Duration ttl = parseDuration(cooldownMetadata.string("ttl"), DEFAULT_TTL);
        final int maxSize = parseInt(cooldownMetadata.string("maxSize"), DEFAULT_MAX_SIZE);

        // Check for valkey sub-config
        final YamlMapping valkey = cooldownMetadata.yamlMapping("valkey");
        if (valkey != null && "true".equalsIgnoreCase(valkey.string("enabled"))) {
            return new FilteredMetadataCacheConfig(
                ttl,
                maxSize,
                true,
                parseInt(valkey.string("l1MaxSize"), DEFAULT_L1_MAX_SIZE),
                parseDuration(valkey.string("l1Ttl"), DEFAULT_L1_TTL),
                parseDuration(valkey.string("l2Ttl"), DEFAULT_L2_TTL),
                parseInt(valkey.string("l2MaxSize"), DEFAULT_L2_MAX_SIZE)
            );
        }

        return new FilteredMetadataCacheConfig(ttl, maxSize, false,
            DEFAULT_L1_MAX_SIZE, DEFAULT_L1_TTL, DEFAULT_L2_TTL, DEFAULT_L2_MAX_SIZE);
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
        if (this.valkeyEnabled) {
            return String.format(
                "FilteredMetadataCacheConfig{ttl=%s, valkeyEnabled=true, l1MaxSize=%d, l1Ttl=%s, l2Ttl=%s, l2Only=%s}",
                this.ttl, this.l1MaxSize, this.l1Ttl, this.l2Ttl, this.isL2OnlyMode()
            );
        }
        return String.format(
            "FilteredMetadataCacheConfig{ttl=%s, maxSize=%d, valkeyEnabled=false}",
            this.ttl, this.maxSize
        );
    }
}
