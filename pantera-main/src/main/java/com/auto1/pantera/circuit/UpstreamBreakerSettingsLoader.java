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
package com.auto1.pantera.circuit;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Loads the OUTBOUND HTTP-client circuit-breaker configuration
 * ({@link CircuitBreakerConfig} — the breaker keyed per upstream
 * {@code scheme://host:port} in {@code JettyClientSlices}) from the DB
 * with env-var and hardcoded fallbacks. Mirrors
 * {@link CircuitBreakerSettingsLoader}, which serves the DISTINCT
 * group-member breaker ({@code AutoBlockSettings}, keyed per member
 * repository) — the two are separate protection layers with separate
 * admin-UI sections and separate key prefixes.
 *
 * <p>Load order per field: DB row ({@code upstream_breaker_*} key in
 * {@code auth_settings}) → env var ({@code PANTERA_UPSTREAM_BREAKER_*})
 * → hardcoded {@link CircuitBreakerConfig#defaults()}. Trip predicates
 * are not configurable — only the numeric gate/backoff knobs.</p>
 *
 * @since 2.2.0
 */
public final class UpstreamBreakerSettingsLoader implements Supplier<CircuitBreakerConfig> {

    static final String KEY_RATE = "upstream_breaker_failure_rate_threshold";
    static final String KEY_MIN_CALLS = "upstream_breaker_minimum_calls";
    static final String KEY_WINDOW = "upstream_breaker_window_seconds";
    static final String KEY_SEED = "upstream_breaker_seed_backoff_seconds";
    static final String KEY_MAX = "upstream_breaker_max_backoff_seconds";

    private static final String ENV_PREFIX = "PANTERA_";

    /**
     * Process-wide singleton installed by {@code VertxMain} after
     * Flyway runs; same lifecycle as
     * {@link CircuitBreakerSettingsLoader#install}.
     */
    private static volatile UpstreamBreakerSettingsLoader installed;

    /**
     * Install a shared loader backed by the given DAO. Idempotent.
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        installed = new UpstreamBreakerSettingsLoader(dao);
    }

    /** Clear the installed loader (tests, shutdown). */
    public static synchronized void uninstall() {
        installed = null;
    }

    /** The installed loader, or {@code null} if none. */
    public static UpstreamBreakerSettingsLoader installed() {
        return installed;
    }

    /**
     * Supplier resolving to the installed loader's settings, falling
     * back to {@link CircuitBreakerConfig#defaults()} when absent
     * (tests, DB-less boots). Safe to call before {@link #install}.
     */
    public static Supplier<CircuitBreakerConfig> activeSupplier() {
        return () -> {
            final UpstreamBreakerSettingsLoader current = installed;
            return current != null ? current.get() : CircuitBreakerConfig.defaults();
        };
    }

    private final AuthSettingsDao dao;
    private final AtomicReference<CircuitBreakerConfig> cached = new AtomicReference<>();

    public UpstreamBreakerSettingsLoader(final AuthSettingsDao dao) {
        this.dao = dao;
    }

    /** Current cached settings, loading from DB on first call. */
    @Override
    public CircuitBreakerConfig get() {
        final CircuitBreakerConfig current = this.cached.get();
        if (current != null) {
            return current;
        }
        final CircuitBreakerConfig loaded = this.load();
        this.cached.compareAndSet(null, loaded);
        return this.cached.get();
    }

    /**
     * Re-read the DB and replace the cached value. Called by the admin
     * endpoint after a successful PUT; every existing breaker reads
     * through the supplier indirection, so the change applies on the
     * next recorded outcome.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    /**
     * Merge DB → env → default per field; invariant violations from the
     * record constructor degrade to pure defaults, never propagate.
     */
    private CircuitBreakerConfig load() {
        final CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        try {
            return new CircuitBreakerConfig(
                Duration.ofSeconds(resolveInt(
                    KEY_SEED, (int) defaults.seedBackoff().toSeconds()
                )),
                Duration.ofSeconds(resolveInt(
                    KEY_MAX, (int) defaults.maxBackoff().toSeconds()
                )),
                defaults.shouldTripOnException(),
                defaults.shouldTripOnStatus(),
                resolveDouble(KEY_RATE, defaults.failureRateThreshold()),
                resolveInt(KEY_MIN_CALLS, defaults.minimumCalls()),
                resolveInt(KEY_WINDOW, defaults.windowSeconds())
            );
        } catch (final IllegalArgumentException ex) {
            return defaults;
        }
    }

    private double resolveDouble(final String key, final double fallback) {
        if (this.dao != null) {
            try {
                final String raw = this.dao.get(key).orElse(null);
                if (raw != null) {
                    return Double.parseDouble(raw);
                }
            } catch (final NumberFormatException ignored) {
                // fall through to env / default
            }
        }
        final String env = System.getenv(ENV_PREFIX + key.toUpperCase(java.util.Locale.ROOT));
        if (env != null) {
            try {
                return Double.parseDouble(env);
            } catch (final NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    private int resolveInt(final String key, final int fallback) {
        if (this.dao != null) {
            final int value = this.dao.getInt(key, Integer.MIN_VALUE);
            if (value != Integer.MIN_VALUE) {
                return value;
            }
        }
        final String env = System.getenv(ENV_PREFIX + key.toUpperCase(java.util.Locale.ROOT));
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (final NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }
}
