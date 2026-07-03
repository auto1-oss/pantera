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
import java.time.Duration;
import java.util.Locale;

/**
 * Configuration for one tier of the {@code ArtifactIndexCache} (positive or
 * negative). Mirrors the shape of {@link NegativeCacheConfig} and reuses the
 * same YAML schema under {@code caches.<sub-key>} so operators can tune
 * positive and negative tiers independently.
 *
 * <p>Two well-known sub-keys: {@code artifact-index-positive} and
 * {@code artifact-index-negative}. Both follow this YAML shape:
 *
 * <pre>
 * caches:
 *   artifact-index-positive:
 *     ttl: 10m
 *     maxSize: 50000
 *     valkey:
 *       enabled: true
 *       l1MaxSize: 50000
 *       l1Ttl: 10m
 *       l2MaxSize: 500000
 *       l2Ttl: 1h
 *       timeout: 50ms
 * </pre>
 *
 * <p>When the YAML sub-key is absent the configured defaults preserve the
 * v2.2.0 hardcoded behaviour: positive 50000 entries / 10 minute TTL,
 * negative 50000 entries / 30 second TTL, both single-tier (Valkey disabled).
 *
 * @since 2.2.0
 */
public final class ArtifactIndexCacheConfig {

    /** YAML sub-key for the positive tier. */
    public static final String POSITIVE_SUBKEY = "artifact-index-positive";

    /** YAML sub-key for the negative tier. */
    public static final String NEGATIVE_SUBKEY = "artifact-index-negative";

    /** Default positive tier TTL — long enough that a typical resolve never re-hits the DB. */
    public static final Duration DEFAULT_POSITIVE_TTL = Duration.ofMinutes(10);

    /** Default negative tier TTL — short enough that publish-then-404 windows close quickly. */
    public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofSeconds(30);

    /** Default L1 max entries (both tiers historically). */
    public static final int DEFAULT_MAX_SIZE = 50_000;

    /** Default L2 max size (informational — Valkey enforces). */
    public static final int DEFAULT_L2_MAX_SIZE = 500_000;

    /** Default L2 positive TTL (1 hour). */
    public static final Duration DEFAULT_L2_POSITIVE_TTL = Duration.ofHours(1);

    /** Default L2 negative TTL (5 minutes). */
    public static final Duration DEFAULT_L2_NEGATIVE_TTL = Duration.ofMinutes(5);

    /** Default L2 operation timeout (fail-fast — must not block the event loop). */
    public static final Duration DEFAULT_L2_TIMEOUT = Duration.ofMillis(50);

    private final Duration ttl;
    private final int maxSize;
    private final boolean valkeyEnabled;
    private final int l1MaxSize;
    private final Duration l1Ttl;
    private final int l2MaxSize;
    private final Duration l2Ttl;
    private final Duration l2Timeout;

    private ArtifactIndexCacheConfig(
        final Duration ttl,
        final int maxSize,
        final boolean valkeyEnabled,
        final int l1MaxSize,
        final Duration l1Ttl,
        final int l2MaxSize,
        final Duration l2Ttl,
        final Duration l2Timeout
    ) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.valkeyEnabled = valkeyEnabled;
        this.l1MaxSize = l1MaxSize;
        this.l1Ttl = l1Ttl;
        this.l2MaxSize = l2MaxSize;
        this.l2Ttl = l2Ttl;
        this.l2Timeout = l2Timeout;
    }

    /**
     * Defaults for the positive tier when YAML is absent.
     *
     * @return positive-tier defaults (50000 / 10m, no Valkey)
     */
    public static ArtifactIndexCacheConfig positiveDefaults() {
        return new ArtifactIndexCacheConfig(
            DEFAULT_POSITIVE_TTL, DEFAULT_MAX_SIZE, false,
            DEFAULT_MAX_SIZE, DEFAULT_POSITIVE_TTL,
            DEFAULT_L2_MAX_SIZE, DEFAULT_L2_POSITIVE_TTL,
            DEFAULT_L2_TIMEOUT
        );
    }

    /**
     * Defaults for the negative tier when YAML is absent.
     *
     * @return negative-tier defaults (50000 / 30s, no Valkey)
     */
    public static ArtifactIndexCacheConfig negativeDefaults() {
        return new ArtifactIndexCacheConfig(
            DEFAULT_NEGATIVE_TTL, DEFAULT_MAX_SIZE, false,
            DEFAULT_MAX_SIZE, DEFAULT_NEGATIVE_TTL,
            DEFAULT_L2_MAX_SIZE, DEFAULT_L2_NEGATIVE_TTL,
            DEFAULT_L2_TIMEOUT
        );
    }

    /**
     * Parse one tier's configuration from the {@code caches:} YAML block.
     *
     * @param caches the {@code caches} YAML mapping (may be {@code null})
     * @param subKey one of {@link #POSITIVE_SUBKEY} / {@link #NEGATIVE_SUBKEY}
     * @return parsed config, or per-tier defaults when the sub-key is absent
     */
    public static ArtifactIndexCacheConfig fromYaml(
        final YamlMapping caches, final String subKey
    ) {
        final ArtifactIndexCacheConfig fallback =
            POSITIVE_SUBKEY.equals(subKey) ? positiveDefaults() : negativeDefaults();
        if (caches == null || subKey == null || subKey.isEmpty()) {
            return fallback;
        }
        final YamlMapping block = caches.yamlMapping(subKey);
        if (block == null) {
            return fallback;
        }
        final Duration ttl = parseDuration(block.string("ttl"), fallback.ttl);
        final int maxSize = parseInt(block.string("maxSize"), fallback.maxSize);
        final YamlMapping valkey = block.yamlMapping("valkey");
        if (valkey != null
            && "true".equalsIgnoreCase(valkey.string("enabled"))) {
            return new ArtifactIndexCacheConfig(
                ttl,
                maxSize,
                true,
                parseInt(valkey.string("l1MaxSize"), maxSize),
                parseDuration(valkey.string("l1Ttl"), ttl),
                parseInt(valkey.string("l2MaxSize"), DEFAULT_L2_MAX_SIZE),
                parseDuration(valkey.string("l2Ttl"), fallback.l2Ttl),
                parseDuration(valkey.string("timeout"), DEFAULT_L2_TIMEOUT)
            );
        }
        return new ArtifactIndexCacheConfig(
            ttl, maxSize, false,
            maxSize, ttl,
            DEFAULT_L2_MAX_SIZE, fallback.l2Ttl, DEFAULT_L2_TIMEOUT
        );
    }

    public Duration ttl() {
        return this.ttl;
    }

    public int maxSize() {
        return this.maxSize;
    }

    public boolean isValkeyEnabled() {
        return this.valkeyEnabled;
    }

    public int l1MaxSize() {
        return this.l1MaxSize;
    }

    public Duration l1Ttl() {
        return this.l1Ttl;
    }

    public int l2MaxSize() {
        return this.l2MaxSize;
    }

    public Duration l2Ttl() {
        return this.l2Ttl;
    }

    public Duration l2Timeout() {
        return this.l2Timeout;
    }

    private static Duration parseDuration(final String value, final Duration defaultVal) {
        if (value == null || value.isEmpty()) {
            return defaultVal;
        }
        try {
            final String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(
                    Long.parseLong(trimmed.substring(0, trimmed.length() - 2))
                );
            }
            if (trimmed.endsWith("d")) {
                return Duration.ofDays(
                    Long.parseLong(trimmed.substring(0, trimmed.length() - 1))
                );
            }
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(
                    Long.parseLong(trimmed.substring(0, trimmed.length() - 1))
                );
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(
                    Long.parseLong(trimmed.substring(0, trimmed.length() - 1))
                );
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(
                    Long.parseLong(trimmed.substring(0, trimmed.length() - 1))
                );
            }
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (final NumberFormatException ex) {
            return defaultVal;
        }
    }

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
            "ArtifactIndexCacheConfig{ttl=%s, maxSize=%d, valkeyEnabled=%s, "
            + "l1MaxSize=%d, l1Ttl=%s, l2MaxSize=%d, l2Ttl=%s, l2Timeout=%s}",
            this.ttl, this.maxSize, this.valkeyEnabled,
            this.l1MaxSize, this.l1Ttl, this.l2MaxSize, this.l2Ttl, this.l2Timeout
        );
    }
}
