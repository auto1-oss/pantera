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
package com.auto1.pantera.http.resilience;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-repo bulkhead registry — single JVM-wide instance that
 * {@code BaseCachedProxySlice} consults to discover the per-repo
 * concurrency gate without taking a constructor-time dependency on
 * pantera-main wiring (T-P12).
 *
 * <p>The registry is intentionally a thin Map facade: bulkheads are
 * created and owned by {@code RepositorySlices.getOrCreateBulkhead},
 * which calls {@link #register(String, RepoBulkhead)} after every
 * creation. Cores' {@code BaseCachedProxySlice} then reads via
 * {@link #bulkheadFor(String)} on each request — a hot-path
 * {@code ConcurrentHashMap.get} (≈ 20 ns).
 *
 * <p>If a repo has no registered bulkhead the response path runs
 * without gating — same behaviour as pre-T-P12 — so the registry is
 * never on the critical path of bring-up.
 *
 * @since 2.2.0
 */
public final class RepoBulkheadRegistry {

    private static final RepoBulkheadRegistry INSTANCE = new RepoBulkheadRegistry();

    private final Map<String, RepoBulkhead> bulkheads = new ConcurrentHashMap<>();

    private RepoBulkheadRegistry() {
    }

    /**
     * @return JVM-wide singleton registry.
     */
    public static RepoBulkheadRegistry instance() {
        return INSTANCE;
    }

    /**
     * Bind {@code bulkhead} as the gate for {@code repoName}. Idempotent —
     * re-registering the same repoName overwrites the previous entry,
     * which is the correct behaviour for hot-reload of the
     * {@code RepositorySlices} config.
     *
     * @param repoName Repository name (non-null).
     * @param bulkhead Bulkhead instance (non-null).
     */
    public void register(final String repoName, final RepoBulkhead bulkhead) {
        if (repoName == null || bulkhead == null) {
            return;
        }
        this.bulkheads.put(repoName, bulkhead);
    }

    /**
     * Look up the registered bulkhead for {@code repoName}.
     *
     * @param repoName Repository name. May be null (returns empty).
     * @return The registered bulkhead, or empty if none is wired for
     *     this repo. Callers (typically {@code BaseCachedProxySlice})
     *     skip gating when empty — same behaviour as the legacy path.
     */
    public Optional<RepoBulkhead> bulkheadFor(final String repoName) {
        if (repoName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.bulkheads.get(repoName));
    }

    /**
     * Drop the registration for {@code repoName}. Called by
     * {@code RepositorySlices} when a repo is unconfigured at runtime.
     * No-op when no entry exists.
     *
     * @param repoName Repository name.
     */
    public void deregister(final String repoName) {
        if (repoName != null) {
            this.bulkheads.remove(repoName);
        }
    }

    /**
     * Number of currently-registered bulkheads. Tests + diagnostics
     * only.
     *
     * @return Map size.
     */
    public int size() {
        return this.bulkheads.size();
    }
}
