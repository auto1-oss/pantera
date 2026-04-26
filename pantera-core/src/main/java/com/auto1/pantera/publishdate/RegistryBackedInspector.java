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
 */
public final class RegistryBackedInspector implements CooldownInspector {

    private final String repoType;
    private final PublishDateRegistry registry;

    public RegistryBackedInspector(final String repoType, final PublishDateRegistry registry) {
        this.repoType = repoType;
        this.registry = registry;
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return this.registry.publishDate(this.repoType, artifact, version);
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
