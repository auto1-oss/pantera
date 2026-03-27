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
package com.auto1.pantera.docker.fake;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manifests implementation with specified tags.
 * Values of parameters `from` and `limit` from last call are captured.
 */
public final class FullTagsManifests implements Manifests {

    /**
     * Tags.
     */
    private final Tags tags;

    /**
     * From parameter captured.
     */
    private final AtomicReference<Pagination> from;

    public FullTagsManifests(final Tags tags) {
        this.tags = tags;
        this.from = new AtomicReference<>();
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content ignored) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        this.from.set(pagination);
        return CompletableFuture.completedFuture(this.tags);
    }

    /**
     * Get captured `from` argument.
     *
     * @return Captured `from` argument.
     */
    public Optional<String> capturedFrom() {
        return Optional.of(ImageTag.validate(this.from.get().last()));
    }

    /**
     * Get captured `limit` argument.
     *
     * @return Captured `limit` argument.
     */
    public int capturedLimit() {
        return from.get().limit();
    }
}
