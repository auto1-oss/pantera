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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * No-op Composer cooldown inspector.
 * Always returns empty results.
 */
final class NoopComposerCooldownInspector implements CooldownInspector {

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
