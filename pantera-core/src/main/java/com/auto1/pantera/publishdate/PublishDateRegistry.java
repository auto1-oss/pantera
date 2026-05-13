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

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves canonical publish dates with the contract:
 * <ol>
 *   <li>L1 (in-process Caffeine, no TTL — immutable) hit → return immediately</li>
 *   <li>L2 (DB {@code artifact_publish_dates} row) hit → populate L1 → return</li>
 *   <li>Source fetch (registry HTTP call) → write to DB and L1 → return</li>
 *   <li>Source returns empty or fails → return {@code Optional.empty()}; do
 *       NOT cache absence (registry might be transiently unreachable, and
 *       publish dates added later are valid retroactively)</li>
 * </ol>
 *
 * <p>Implementations are thread-safe and meant to be a long-lived singleton.
 *
 * <p>Track 5 Phase 2A introduced {@link Mode}: callers on a cache-hit hot
 * path that nevertheless want to consult the registry can pass
 * {@link Mode#CACHE_ONLY} to physically prevent the step-3 source fetch.
 * This is defence-in-depth — Phase 1A removes the cooldown-on-hit call sites
 * that were exercising step 3, but {@code CACHE_ONLY} makes upstream HEAD
 * impossible from those call sites going forward.
 */
public interface PublishDateRegistry {

    /**
     * Lookup mode controlling whether to fall back to the upstream source
     * when L1 and L2 both miss. Defaults to {@link #NETWORK_FALLBACK}.
     */
    enum Mode {
        /** L1 → L2 → upstream source. Pre-Track-5 behaviour. */
        NETWORK_FALLBACK,
        /**
         * L1 → L2 → {@code Optional.empty()}. Step 3 (upstream source HTTP
         * fetch) is skipped entirely. Used by cache-hit paths to guarantee
         * zero upstream I/O while still consulting the local registry.
         */
        CACHE_ONLY
    }

    /**
     * Resolve publish date for {@code (repoType, name, version)} with default
     * {@link Mode#NETWORK_FALLBACK}. Existing pre-Track-5 contract; remains
     * the abstract method so simple lambda implementations (test stubs,
     * placeholder default in {@link PublishDateRegistries}) still satisfy
     * the interface without surgery.
     *
     * @param repoType Repository type ("maven", "npm", "pypi", "go", "composer", "gem")
     * @param name Artifact name (per Pantera's repo-type-specific naming convention)
     * @param version Artifact version
     * @return future of optional canonical publish date
     */
    CompletableFuture<Optional<Instant>> publishDate(
        String repoType, String name, String version
    );

    /**
     * Resolve publish date for {@code (repoType, name, version)} with an
     * explicit lookup mode. {@code CACHE_ONLY} forbids the upstream-source
     * fallback so a cache-hit hot path can never trigger network I/O via the
     * registry.
     *
     * <p>Default implementation just delegates to the 3-arg method
     * (NETWORK_FALLBACK semantics). Registries that genuinely fetch upstream
     * (like {@code DbPublishDateRegistry}) override to short-circuit on
     * {@code CACHE_ONLY} after the L2 lookup.
     */
    default CompletableFuture<Optional<Instant>> publishDate(
        final String repoType, final String name, final String version, final Mode mode
    ) {
        return this.publishDate(repoType, name, version);
    }
}
