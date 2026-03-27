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
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Manifests implementation that fails to get manifest.
 */
public final class FaultyGetManifests implements Manifests {

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        return CompletableFuture.failedFuture(new IllegalStateException());
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        throw new UnsupportedOperationException();
    }
}
