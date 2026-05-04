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
            JsonReads.boolOr(rows, "prefetch.enabled", true),
            JsonReads.intOr(rows, "prefetch.concurrency.global", 64),
            JsonReads.intOr(rows, "prefetch.concurrency.per_upstream", 16),
            JsonReads.intOr(rows, "prefetch.queue.capacity", 2048),
            JsonReads.intOr(rows, "prefetch.worker_threads", 8)
        );
    }
}
