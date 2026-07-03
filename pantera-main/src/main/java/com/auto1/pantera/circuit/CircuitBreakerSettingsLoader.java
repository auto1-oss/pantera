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
import com.auto1.pantera.http.timeout.AutoBlockSettings;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Loads {@link AutoBlockSettings} from the DB with env-var and hardcoded
 * fallbacks, caches the value in memory, and supports cache invalidation
 * when the admin settings UI updates the DB rows.
 *
 * <p>Load order for each field:</p>
 * <ol>
 *   <li>DB row in {@code auth_settings} with the {@code circuit_breaker_*}
 *       prefix — if present and parseable, wins.</li>
 *   <li>Environment variable (same keys uppercased with {@code PANTERA_}
 *       prefix: {@code PANTERA_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD}, etc.)
 *       — for operators who want to override without touching the DB.</li>
 *   <li>Hardcoded default from {@link AutoBlockSettings#defaults()}.</li>
 * </ol>
 *
 * <p>Thread-safety: {@link #get()} is safe to call from any thread; the
 * cached settings reference is held in an {@link AtomicReference}. Settings
 * validation (via the {@code AutoBlockSettings} record constructor) runs
 * at load time — an invalid DB value falls through to the env/default
 * chain rather than blowing up a request.</p>
 *
 * @since 2.2.0
 */
public final class CircuitBreakerSettingsLoader implements Supplier<AutoBlockSettings> {

    static final String KEY_RATE = "circuit_breaker_failure_rate_threshold";
    static final String KEY_MIN_CALLS = "circuit_breaker_minimum_number_of_calls";
    static final String KEY_WINDOW = "circuit_breaker_sliding_window_seconds";
    static final String KEY_INITIAL_BLOCK = "circuit_breaker_initial_block_seconds";
    static final String KEY_MAX_BLOCK = "circuit_breaker_max_block_seconds";

    private static final String ENV_PREFIX = "PANTERA_";

    /**
     * Process-wide singleton installed by {@code VertxMain} after the
     * Flyway migrations run. Holds the loader so both
     * {@code RepositorySlices} (which needs a {@link Supplier}) and the
     * admin PUT endpoint (which invalidates on write) reference the
     * same in-memory cache. {@code null} until {@link #install} is
     * called; callers use {@link #activeSupplier()} which falls back to
     * {@link AutoBlockSettings#defaults()} when absent (tests, DB-less
     * boots).
     */
    private static volatile CircuitBreakerSettingsLoader installed;

    /**
     * Install a shared loader backed by the given DAO. Idempotent:
     * calling twice replaces the previous instance. Intended to be
     * called once at startup from {@code VertxMain}.
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        installed = new CircuitBreakerSettingsLoader(dao);
    }

    /** Clear the installed loader (tests, shutdown). */
    public static synchronized void uninstall() {
        installed = null;
    }

    /** The installed loader, or {@code null} if none. */
    public static CircuitBreakerSettingsLoader installed() {
        return installed;
    }

    /**
     * Returns a supplier that resolves to the current installed
     * settings if any, falling back to hardcoded defaults otherwise.
     * Safe to call at any time including before {@link #install}.
     */
    public static Supplier<AutoBlockSettings> activeSupplier() {
        return () -> {
            final CircuitBreakerSettingsLoader current = installed;
            return current != null ? current.get() : AutoBlockSettings.defaults();
        };
    }

    private final AuthSettingsDao dao;
    private final AtomicReference<AutoBlockSettings> cached = new AtomicReference<>();

    public CircuitBreakerSettingsLoader(final AuthSettingsDao dao) {
        this.dao = dao;
    }

    /** Current cached settings, loading from DB on first call. */
    @Override
    public AutoBlockSettings get() {
        final AutoBlockSettings current = this.cached.get();
        if (current != null) {
            return current;
        }
        final AutoBlockSettings loaded = this.load();
        this.cached.compareAndSet(null, loaded);
        return this.cached.get();
    }

    /**
     * Re-read the DB and replace the cached value. Called by the admin
     * endpoint after a successful PUT so the next {@link #get()} sees the
     * fresh values. Safe to call from any thread.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    /**
     * Merge DB → env → default for each field and build the record.
     * Catches {@link IllegalArgumentException} from the record constructor
     * and falls back to pure defaults rather than propagating.
     */
    private AutoBlockSettings load() {
        final AutoBlockSettings defaults = AutoBlockSettings.defaults();
        try {
            return new AutoBlockSettings(
                resolveDouble(KEY_RATE, defaults.failureRateThreshold()),
                resolveInt(KEY_MIN_CALLS, defaults.minimumNumberOfCalls()),
                resolveInt(KEY_WINDOW, defaults.slidingWindowSeconds()),
                Duration.ofSeconds(resolveInt(
                    KEY_INITIAL_BLOCK,
                    (int) defaults.initialBlockDuration().toSeconds()
                )),
                Duration.ofSeconds(resolveInt(
                    KEY_MAX_BLOCK,
                    (int) defaults.maxBlockDuration().toSeconds()
                ))
            );
        } catch (final IllegalArgumentException ex) {
            // Invariants failed (e.g. operator set failureRateThreshold to 2.0
            // via the API before the validator fix was deployed). Don't take
            // the process down — degrade to pure defaults.
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
