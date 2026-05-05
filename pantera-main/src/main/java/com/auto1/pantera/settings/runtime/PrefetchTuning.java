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

import java.util.Locale;
import java.util.Map;
import javax.json.JsonObject;

/**
 * Immutable typed snapshot of prefetch tunables sourced from the
 * {@code settings} table. Constructed via {@link #defaults()} or
 * {@link #fromMap(Map)}; never mutated.
 *
 * <p>The {@code perUpstreamByEcosystem} map carries optional per-ecosystem
 * overrides for the per-upstream concurrency cap. It is keyed by the
 * lower-cased ecosystem name (e.g. {@code "maven"}, {@code "npm"}) and
 * looked up via {@link #perUpstreamFor(String)}; ecosystems missing from
 * the map fall back to the global {@link #perUpstreamConcurrency()}.
 *
 * @since 2.2.0
 */
public record PrefetchTuning(
    boolean enabled,
    int globalConcurrency,
    int perUpstreamConcurrency,
    Map<String, Integer> perUpstreamByEcosystem,
    int queueCapacity,
    int workerThreads
) {
    public static PrefetchTuning defaults() {
        return new PrefetchTuning(
            true, 64, 16,
            Map.of("maven", 16, "gradle", 16, "npm", 32),
            2048, 8
        );
    }

    /**
     * Resolve the effective per-upstream cap for a given ecosystem name.
     * Lookup is case-insensitive on the ecosystem name; an unknown or
     * {@code null} ecosystem returns the global default.
     *
     * @param ecosystem Ecosystem name (e.g. {@code "MAVEN"} from
     *                  {@code Coordinate.Ecosystem.name()}). May be null.
     * @return The per-upstream concurrency cap to enforce.
     */
    public int perUpstreamFor(final String ecosystem) {
        if (ecosystem == null) {
            return this.perUpstreamConcurrency;
        }
        return this.perUpstreamByEcosystem.getOrDefault(
            ecosystem.toLowerCase(Locale.ROOT),
            this.perUpstreamConcurrency
        );
    }

    public static PrefetchTuning fromMap(final Map<String, JsonObject> rows) {
        final Map<String, Integer> byEco = Map.of(
            "maven",  JsonReads.intOr(rows, "prefetch.concurrency.per_upstream.maven",  16),
            "gradle", JsonReads.intOr(rows, "prefetch.concurrency.per_upstream.gradle", 16),
            "npm",    JsonReads.intOr(rows, "prefetch.concurrency.per_upstream.npm",    32)
        );
        return new PrefetchTuning(
            JsonReads.boolOr(rows, "prefetch.enabled", true),
            JsonReads.intOr(rows, "prefetch.concurrency.global", 64),
            JsonReads.intOr(rows, "prefetch.concurrency.per_upstream", 16),
            byEco,
            JsonReads.intOr(rows, "prefetch.queue.capacity", 2048),
            JsonReads.intOr(rows, "prefetch.worker_threads", 8)
        );
    }
}
