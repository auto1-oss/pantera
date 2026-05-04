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

import java.util.Map;
import javax.json.JsonObject;

/**
 * Immutable typed snapshot of prefetch circuit-breaker tunables sourced
 * from the {@code settings} table. Constructed via {@link #defaults()} or
 * {@link #fromMap(Map)}; never mutated.
 */
public record CircuitBreakerTuning(
    int dropThresholdPerSec,
    int windowSeconds,
    int disableMinutes
) {
    public static CircuitBreakerTuning defaults() {
        return new CircuitBreakerTuning(100, 30, 5);
    }

    public static CircuitBreakerTuning fromMap(final Map<String, JsonObject> rows) {
        return new CircuitBreakerTuning(
            PrefetchTuning.getInt(rows, "prefetch.circuit_breaker.drop_threshold_per_sec", 100),
            PrefetchTuning.getInt(rows, "prefetch.circuit_breaker.window_seconds", 30),
            PrefetchTuning.getInt(rows, "prefetch.circuit_breaker.disable_minutes", 5)
        );
    }
}
