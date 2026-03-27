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

import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-read {@link Layers} implementation.
 */
public final class MultiReadLayers implements Layers {

    /**
     * Layers for reading.
     */
    private final List<Layers> layers;

    /**
     * @param layers Layers for reading.
     */
    public MultiReadLayers(final List<Layers> layers) {
        this.layers = layers;
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        final CompletableFuture<Optional<Blob>> promise = new CompletableFuture<>();
        CompletableFuture.allOf(
            this.layers.stream()
                .map(
                    layer -> layer.get(digest)
                        .thenAccept(
                            opt -> {
                                if (opt.isPresent()) {
                                    promise.complete(opt);
                                }
                            }
                        )
                        .toCompletableFuture()
                )
                .toArray(CompletableFuture[]::new)
        ).handle(
            (nothing, throwable) -> promise.complete(Optional.empty())
        );
        return promise;
    }
}
