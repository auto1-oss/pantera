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
package com.auto1.pantera.publishdate.sources;

import com.auto1.pantera.publishdate.PublishDateSource;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Composes a primary {@link PublishDateSource} with a fallback.
 *
 * <p>The primary source is tried first. If it returns {@code Optional.empty()}
 * or completes exceptionally, the fallback source is queried. If both fail,
 * the fallback's exception is propagated to the caller.</p>
 *
 * <p>Example: {@link MavenHeadSource} (fast, ~67ms) as primary,
 * {@link JFrogStorageApiSource} (~500ms) as fallback. ~95% of artifacts
 * resolve via the fast primary path.</p>
 */
public final class ChainedPublishDateSource implements PublishDateSource {

    private final PublishDateSource primary;
    private final PublishDateSource fallback;

    public ChainedPublishDateSource(
        final PublishDateSource primary,
        final PublishDateSource fallback
    ) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String repoType() {
        return this.primary.repoType();
    }

    @Override
    public String sourceId() {
        return this.primary.sourceId() + "+" + this.fallback.sourceId();
    }

    @Override
    public CompletableFuture<Optional<Instant>> fetch(
        final String name,
        final String version
    ) {
        return this.primary.fetch(name, version)
            .thenCompose(opt -> {
                if (opt.isPresent()) {
                    return CompletableFuture.completedFuture(opt);
                }
                return this.fallback.fetch(name, version);
            })
            .exceptionallyCompose(err ->
                this.fallback.fetch(name, version)
            );
    }
}
