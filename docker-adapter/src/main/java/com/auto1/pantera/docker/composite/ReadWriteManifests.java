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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read-write {@link Manifests} implementation.
 */
public final class ReadWriteManifests implements Manifests {

    /**
     * Manifests for reading.
     */
    private final Manifests read;

    /**
     * Manifests for writing.
     */
    private final Manifests write;

    /**
     * @param read Manifests for reading.
     * @param write Manifests for writing.
     */
    public ReadWriteManifests(final Manifests read, final Manifests write) {
        this.read = read;
        this.write = write;
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        return this.write.put(ref, content);
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        return this.read.get(ref);
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        return this.read.tags(pagination);
    }
}
