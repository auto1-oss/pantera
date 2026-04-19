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
import com.auto1.pantera.http.misc.ConfigDefaults;
import java.util.Optional;

/**
 * Global cache configuration holder.
 * Provides shared Valkey connection for all caches across Pantera, and
 * exposes per-cache configuration records (e.g. {@link #authEnabled()})
 * resolved from env → YAML → compile-time defaults.
 * Thread-safe singleton pattern.
 *
 * @since 1.0
 */
public final class GlobalCacheConfig {

    // -------------------------------------------------------------
    // Compile-time defaults for auth-enabled cache (fallback-only).
    // Env vars and YAML override these.
    // -------------------------------------------------------------

    /** Default L1 max size for the auth-enabled cache. */
    static final int DEFAULT_AUTH_ENABLED_L1_MAX_SIZE = 10_000;

    /** Default L1 TTL (seconds) for the auth-enabled cache. */
    static final int DEFAULT_AUTH_ENABLED_L1_TTL_SECONDS = 300;

    /** Default flag: L2 (Valkey) enabled for the auth-enabled cache. */
    static final boolean DEFAULT_AUTH_ENABLED_L2_ENABLED = true;

    /** Default L2 TTL (seconds) for the auth-enabled cache. */
    static final int DEFAULT_AUTH_ENABLED_L2_TTL_SECONDS = 3_600;

    /** Default L2 operation timeout (ms) for the auth-enabled cache. */
    static final int DEFAULT_AUTH_ENABLED_L2_TIMEOUT_MS = 100;

    /**
     * Singleton instance.
     */
    private static volatile GlobalCacheConfig instance;

    /**
     * Shared Valkey connection.
     */
    private final ValkeyConnection valkey;

    /**
     * Optional {@code caches} YAML mapping (from {@code meta.caches}).
     * May be {@code null} when no config is provided; all accessors
     * fall back to env / compile-time defaults in that case.
     */
    private final YamlMapping caches;

    /**
     * Private constructor for singleton.
     * @param valkey Valkey connection
     * @param caches Optional YAML {@code caches} mapping
     */
    private GlobalCacheConfig(final ValkeyConnection valkey, final YamlMapping caches) {
        this.valkey = valkey;
        this.caches = caches;
    }

    /**
     * Initialize global cache configuration.
     * Should be called once at startup by YamlSettings.
     *
     * @param valkey Optional Valkey connection
     */
    public static void initialize(final Optional<ValkeyConnection> valkey) {
        GlobalCacheConfig.initialize(valkey, null);
    }

    /**
     * Initialize global cache configuration with YAML {@code caches} mapping.
     *
     * @param valkey Optional Valkey connection
     * @param caches Optional YAML {@code caches} mapping (from {@code meta.caches})
     */
    public static void initialize(
        final Optional<ValkeyConnection> valkey,
        final YamlMapping caches
    ) {
        if (instance == null) {
            synchronized (GlobalCacheConfig.class) {
                if (instance == null) {
                    instance = new GlobalCacheConfig(valkey.orElse(null), caches);
                }
            }
        }
    }

    /**
     * Get the shared Valkey connection.
     * @return Optional Valkey connection
     */
    public static Optional<ValkeyConnection> valkeyConnection() {
        if (instance == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(instance.valkey);
    }

    /**
     * Get the singleton instance, creating a defaults-only one if the
     * config has not been explicitly initialized yet. Callers that need
     * to read config sections (e.g. {@link #authEnabled()}) should use
     * this accessor.
     *
     * @return Singleton instance (never null)
     */
    public static GlobalCacheConfig getInstance() {
        if (instance == null) {
            synchronized (GlobalCacheConfig.class) {
                if (instance == null) {
                    instance = new GlobalCacheConfig(null, null);
                }
            }
        }
        return instance;
    }

    /**
     * Configuration for the auth-enabled cache
     * (wraps {@code LocalEnabledFilter}).
     *
     * @param l1MaxSize L1 Caffeine cache max entries
     * @param l1TtlSeconds L1 TTL in seconds
     * @param l2Enabled Whether L2 (Valkey) tier is enabled
     * @param l2TtlSeconds L2 TTL in seconds
     * @param l2TimeoutMs L2 operation timeout in milliseconds
     */
    public record AuthEnabledConfig(
        int l1MaxSize,
        int l1TtlSeconds,
        boolean l2Enabled,
        int l2TtlSeconds,
        int l2TimeoutMs
    ) { }

    /**
     * Resolve auth-enabled cache configuration with precedence
     * env → YAML ({@code meta.caches.auth-enabled.*}) → default.
     *
     * <p>YAML paths:
     * <ul>
     *   <li>{@code auth-enabled.l1.maxSize}</li>
     *   <li>{@code auth-enabled.l1.ttlSeconds}</li>
     *   <li>{@code auth-enabled.l2.enabled}</li>
     *   <li>{@code auth-enabled.l2.ttlSeconds}</li>
     *   <li>{@code auth-enabled.l2.timeoutMs}</li>
     * </ul>
     *
     * <p>Env overrides: {@code PANTERA_AUTH_ENABLED_L1_SIZE},
     * {@code PANTERA_AUTH_ENABLED_L1_TTL_SECONDS},
     * {@code PANTERA_AUTH_ENABLED_L2_ENABLED},
     * {@code PANTERA_AUTH_ENABLED_L2_TTL_SECONDS},
     * {@code PANTERA_AUTH_ENABLED_L2_TIMEOUT_MS}.
     *
     * @return Resolved config
     */
    public AuthEnabledConfig authEnabled() {
        // YAML values (may be null if YAML not provided or keys missing)
        Integer yL1Size = null;
        Integer yL1Ttl = null;
        Boolean yL2Enabled = null;
        Integer yL2Ttl = null;
        Integer yL2Timeout = null;
        if (this.caches != null) {
            final YamlMapping authSection = this.caches.yamlMapping("auth-enabled");
            if (authSection != null) {
                final YamlMapping l1 = authSection.yamlMapping("l1");
                if (l1 != null) {
                    yL1Size = parseIntOrNull(l1.string("maxSize"));
                    yL1Ttl = parseIntOrNull(l1.string("ttlSeconds"));
                }
                final YamlMapping l2 = authSection.yamlMapping("l2");
                if (l2 != null) {
                    yL2Enabled = parseBooleanOrNull(l2.string("enabled"));
                    yL2Ttl = parseIntOrNull(l2.string("ttlSeconds"));
                    yL2Timeout = parseIntOrNull(l2.string("timeoutMs"));
                }
            }
        }
        // Env → YAML → default (ConfigDefaults.getX reads env/sysprop; if
        // neither is present it returns the second arg, which we set to
        // the YAML value when present, else the compile-time default).
        final int l1Size = ConfigDefaults.getInt(
            "PANTERA_AUTH_ENABLED_L1_SIZE",
            yL1Size != null ? yL1Size : DEFAULT_AUTH_ENABLED_L1_MAX_SIZE
        );
        final int l1Ttl = ConfigDefaults.getInt(
            "PANTERA_AUTH_ENABLED_L1_TTL_SECONDS",
            yL1Ttl != null ? yL1Ttl : DEFAULT_AUTH_ENABLED_L1_TTL_SECONDS
        );
        final boolean l2Enabled = ConfigDefaults.getBoolean(
            "PANTERA_AUTH_ENABLED_L2_ENABLED",
            yL2Enabled != null ? yL2Enabled : DEFAULT_AUTH_ENABLED_L2_ENABLED
        );
        final int l2Ttl = ConfigDefaults.getInt(
            "PANTERA_AUTH_ENABLED_L2_TTL_SECONDS",
            yL2Ttl != null ? yL2Ttl : DEFAULT_AUTH_ENABLED_L2_TTL_SECONDS
        );
        final int l2Timeout = ConfigDefaults.getInt(
            "PANTERA_AUTH_ENABLED_L2_TIMEOUT_MS",
            yL2Timeout != null ? yL2Timeout : DEFAULT_AUTH_ENABLED_L2_TIMEOUT_MS
        );
        return new AuthEnabledConfig(l1Size, l1Ttl, l2Enabled, l2Ttl, l2Timeout);
    }

    private static Integer parseIntOrNull(final String val) {
        if (val == null || val.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(val.trim());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean parseBooleanOrNull(final String val) {
        if (val == null || val.isEmpty()) {
            return null;
        }
        final String v = val.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Reset for testing purposes.
     */
    static void reset() {
        instance = null;
    }
}
