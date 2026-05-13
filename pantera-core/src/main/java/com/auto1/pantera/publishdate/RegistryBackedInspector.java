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

import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Single {@link CooldownInspector} implementation backed by a {@link PublishDateRegistry}.
 * Replaces all per-adapter inspector implementations.
 *
 * <p>{@link #dependencies} returns empty — transitive dependency cooldown
 * propagation is not currently used (see comment in
 * {@code JdbcCooldownService}: "No dependencies tracked anymore").
 *
 * <p>Track 5 Phase 2A: the inspector now carries a
 * {@link PublishDateRegistry.Mode} so callers on the cache-hit hot path can
 * opt into {@link PublishDateRegistry.Mode#CACHE_ONLY}, which forbids the
 * registry's upstream-source fallback. Cache-miss callers continue to use
 * {@link PublishDateRegistry.Mode#NETWORK_FALLBACK} (the default) so a
 * brand-new artifact still resolves its publish date via the upstream
 * source on first request.
 */
public final class RegistryBackedInspector implements CooldownInspector {

    private final String repoType;
    private final PublishDateRegistry registry;
    private final PublishDateRegistry.Mode mode;

    /**
     * Build a network-fallback inspector — pre-Track-5 behaviour.
     */
    public RegistryBackedInspector(final String repoType, final PublishDateRegistry registry) {
        this(repoType, registry, PublishDateRegistry.Mode.NETWORK_FALLBACK);
    }

    /**
     * Build an inspector with the chosen lookup mode.
     *
     * @param repoType  Repository type (maven, npm, ...).
     * @param registry  Backing registry.
     * @param mode      {@link PublishDateRegistry.Mode#CACHE_ONLY} for cache-hit
     *                  call sites; {@link PublishDateRegistry.Mode#NETWORK_FALLBACK}
     *                  for cache-miss / cooldown-on-fetch call sites.
     */
    public RegistryBackedInspector(
        final String repoType,
        final PublishDateRegistry registry,
        final PublishDateRegistry.Mode mode
    ) {
        this.repoType = repoType;
        this.registry = registry;
        this.mode = mode;
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return this.registry.publishDate(this.repoType, artifact, version, this.mode);
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
