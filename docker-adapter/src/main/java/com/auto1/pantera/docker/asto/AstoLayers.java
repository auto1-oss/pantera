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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asto implementation of {@link Layers}.
 */
public final class AstoLayers implements Layers {

    /**
     * Blobs storage.
     */
    private final Blobs blobs;

    /**
     * @param blobs Blobs storage.
     */
    public AstoLayers(Blobs blobs) {
        this.blobs = blobs;
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        return this.blobs.put(source);
    }

    @Override
    public CompletableFuture<Void> mount(Blob blob) {
        return blob.content()
            .thenCompose(content -> blobs.put(new TrustedBlobSource(content, blob.digest())))
            .thenRun(() -> {
                // No-op
            });
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return this.blobs.blob(digest);
    }
}
