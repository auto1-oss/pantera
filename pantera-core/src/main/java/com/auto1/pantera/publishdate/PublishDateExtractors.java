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
package com.auto1.pantera.publishdate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Track 5 Phase 3B: process-wide registry keyed by repo-type that returns
 * the registered {@link PublishDateExtractor} for that ecosystem. Each
 * adapter calls {@link #register(String, PublishDateExtractor)} exactly
 * once at boot (typically during {@code VertxMain.start} alongside the
 * inspector wiring) and the proxy-cache event path resolves
 * {@code (repoType, headers, name, version)} through {@link #extract}.
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap}. Registrations are
 * set-once-and-stay; concurrent reads are lock-free. Tests that exercise
 * the registry directly should call {@link #clear()} in {@code @AfterEach}
 * to keep Surefire forks isolated, mirroring the
 * {@link com.auto1.pantera.http.cache.CacheWriteCallbackRegistry} hygiene
 * convention.
 *
 * <p>Lookup misses return a default extractor that yields
 * {@link Optional#empty()} unconditionally — adapters not yet migrated to
 * the SPI keep working with the pre-Track-5 fallback (event consumer's
 * {@code System.currentTimeMillis()}), so the rollout is additive.
 *
 * @since 2.2.0
 */
public final class PublishDateExtractors {

    /**
     * Default extractor returned when no specific extractor is registered
     * for a repo-type. Always returns empty so the caller falls back to its
     * pre-Track-5 behaviour without errors.
     */
    private static final PublishDateExtractor NO_OP =
        (headers, name, version) -> Optional.empty();

    /** Singleton instance. */
    private static final PublishDateExtractors INSTANCE = new PublishDateExtractors();

    private final Map<String, PublishDateExtractor> byRepoType = new ConcurrentHashMap<>();

    private PublishDateExtractors() {
    }

    /**
     * @return Process-wide singleton.
     */
    public static PublishDateExtractors instance() {
        return INSTANCE;
    }

    /**
     * Register an extractor for a repo-type. Last writer wins (useful for
     * tests; production is set-once at boot).
     *
     * @param repoType  Repository type identifier ("maven", "npm", ...).
     * @param extractor Implementation to register.
     */
    public void register(final String repoType, final PublishDateExtractor extractor) {
        if (repoType == null || extractor == null) {
            throw new IllegalArgumentException("repoType / extractor must be non-null");
        }
        this.byRepoType.put(repoType, extractor);
    }

    /**
     * @return Currently registered extractor for {@code repoType}, or a
     *         no-op extractor (never {@code null}) so callers can use the
     *         result without null checks.
     */
    public PublishDateExtractor forRepoType(final String repoType) {
        final PublishDateExtractor reg = this.byRepoType.get(repoType);
        return reg == null ? NO_OP : reg;
    }

    /**
     * Clear all registrations (test-only). Production code MUST NOT call
     * this — the registrations are part of the boot wiring and clearing
     * them at runtime would break every adapter's publish-date pipeline.
     */
    public void clear() {
        this.byRepoType.clear();
    }
}
