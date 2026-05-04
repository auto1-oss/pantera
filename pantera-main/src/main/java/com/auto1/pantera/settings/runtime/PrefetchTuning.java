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
 * Immutable typed snapshot of prefetch tunables sourced from the
 * {@code settings} table. Constructed via {@link #defaults()} or
 * {@link #fromMap(Map)}; never mutated.
 */
public record PrefetchTuning(
    boolean enabled,
    int globalConcurrency,
    int perUpstreamConcurrency,
    int queueCapacity,
    int workerThreads
) {
    public static PrefetchTuning defaults() {
        return new PrefetchTuning(true, 64, 16, 2048, 8);
    }

    public static PrefetchTuning fromMap(final Map<String, JsonObject> rows) {
        return new PrefetchTuning(
            getBool(rows, "prefetch.enabled", true),
            getInt(rows, "prefetch.concurrency.global", 64),
            getInt(rows, "prefetch.concurrency.per_upstream", 16),
            getInt(rows, "prefetch.queue.capacity", 2048),
            getInt(rows, "prefetch.worker_threads", 8)
        );
    }

    static boolean getBool(final Map<String, JsonObject> r, final String k, final boolean d) {
        final JsonObject row = r.get(k);
        return row == null ? d : row.getBoolean("value");
    }

    static int getInt(final Map<String, JsonObject> r, final String k, final int d) {
        final JsonObject row = r.get(k);
        return row == null ? d : row.getInt("value");
    }
}
