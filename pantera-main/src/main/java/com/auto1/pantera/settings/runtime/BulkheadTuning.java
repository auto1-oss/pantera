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
package com.auto1.pantera.settings.runtime;

import com.auto1.pantera.http.resilience.AdaptiveBulkheadLimits;
import java.time.Duration;
import java.util.Map;
import javax.json.JsonObject;

/**
 * Immutable typed snapshot of bulkhead tunables sourced from the
 * {@code settings} table. Constructed via {@link #defaults()} or
 * {@link #fromMap(Map)}; never mutated. The {@code RuntimeSettingsCache}
 * hands the resulting record to {@code RepositorySlices} on every
 * bulkhead (re)creation.
 *
 * <p>Admins edit every upstream-facing concurrency parameter from one
 * place in the settings UI; changes flow into existing bulkheads on the
 * next acquire via {@code RuntimeSettingsCache}'s NOTIFY listener.
 *
 * @param adaptive         {@code true} to enable AIMD tuning; {@code false} for fixed permits
 * @param minPermits       Lower bound on the dynamic permit count
 * @param maxPermits       Upper bound on the dynamic permit count (hard cap on concurrency)
 * @param initialPermits   Starting permit count when the bulkhead is created or recreated
 * @param targetP99Millis  Per-op latency target the controller compares against the window peak
 * @param windowSeconds    AIMD evaluation interval, in seconds
 * @param rampUpStep       Permits added per healthy window
 * @param rampDownFactor   Multiplier applied to permits on a bad window (in {@code (0, 1)})
 * @since 2.2.0
 */
public record BulkheadTuning(
    boolean adaptive,
    int minPermits,
    int maxPermits,
    int initialPermits,
    long targetP99Millis,
    int windowSeconds,
    int rampUpStep,
    double rampDownFactor
) {

    /** Default queue depth for the per-repo drain pool — not surfaced as a tunable yet. */
    private static final int DEFAULT_QUEUE_DEPTH = 1000;
    /** Default Retry-After hint when the gate rejects — not surfaced as a tunable yet. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    /**
     * Spec defaults — keep in sync with {@link SettingsKey} and
     * {@link AdaptiveBulkheadLimits#defaults()}.
     *
     * @return Default tuning suitable for a typical proxy upstream.
     */
    public static BulkheadTuning defaults() {
        return new BulkheadTuning(
            true,
            5,
            100,
            10,
            500L,
            5,
            1,
            0.5
        );
    }

    /**
     * Bind tunables from the runtime settings rows.
     *
     * @param rows Snapshot of the {@code settings} table.
     * @return Tunables with DB-overridden fields where rows exist, defaults elsewhere.
     */
    public static BulkheadTuning fromMap(final Map<String, JsonObject> rows) {
        final BulkheadTuning defaults = defaults();
        return new BulkheadTuning(
            JsonReads.valueOr(rows, "http_client.bulkhead.adaptive",
                v -> v.getBoolean("value"), defaults.adaptive()),
            JsonReads.valueOr(rows, "http_client.bulkhead.min_permits",
                v -> v.getInt("value"), defaults.minPermits()),
            JsonReads.valueOr(rows, "http_client.bulkhead.max_permits",
                v -> v.getInt("value"), defaults.maxPermits()),
            JsonReads.valueOr(rows, "http_client.bulkhead.initial_permits",
                v -> v.getInt("value"), defaults.initialPermits()),
            JsonReads.valueOr(rows, "http_client.bulkhead.target_p99_ms",
                v -> Long.valueOf(v.getInt("value")), defaults.targetP99Millis()),
            JsonReads.valueOr(rows, "http_client.bulkhead.window_seconds",
                v -> v.getInt("value"), defaults.windowSeconds()),
            JsonReads.valueOr(rows, "http_client.bulkhead.ramp_up_step",
                v -> v.getInt("value"), defaults.rampUpStep()),
            JsonReads.valueOr(rows, "http_client.bulkhead.ramp_down_factor",
                v -> v.getJsonNumber("value").doubleValue(), defaults.rampDownFactor())
        );
    }

    /**
     * Convert this typed snapshot into the {@link AdaptiveBulkheadLimits}
     * record that {@code RepoBulkhead} consumes. Clamps {@code initialPermits}
     * into {@code [minPermits, maxPermits]} defensively so a stale DB row
     * cannot crash bulkhead construction.
     *
     * @return Adaptive limits ready to hand to {@code new RepoBulkhead(...)}.
     */
    public AdaptiveBulkheadLimits toLimits() {
        final int min = Math.max(1, this.minPermits);
        final int max = Math.max(min, this.maxPermits);
        final int initial = Math.max(min, Math.min(max, this.initialPermits));
        return new AdaptiveBulkheadLimits(
            this.adaptive,
            min,
            max,
            initial,
            Math.max(1L, this.targetP99Millis),
            Math.max(1, this.windowSeconds),
            Math.max(1, this.rampUpStep),
            clampFactor(this.rampDownFactor),
            DEFAULT_QUEUE_DEPTH,
            DEFAULT_RETRY_AFTER
        );
    }

    private static double clampFactor(final double value) {
        if (value <= 0.0) {
            return 0.1;
        }
        if (value >= 1.0) {
            return 0.9;
        }
        return value;
    }
}
