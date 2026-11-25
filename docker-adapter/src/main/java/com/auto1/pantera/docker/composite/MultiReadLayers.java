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
import java.util.concurrent.atomic.AtomicReference;

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
    public CompletableFuture<Void> delete(final Digest digest) {
        // Deletes target the authoritative (local) store only — see
        // WS4-docker.5 §3. Maps to 405 via ErrorHandlingSlice.
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        final CompletableFuture<Optional<Blob>> promise = new CompletableFuture<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture.allOf(
            this.layers.stream()
                .map(
                    layer -> MultiReadLayers.read(layer, digest)
                        .handle(
                            (opt, err) -> {
                                if (err != null) {
                                    failure.accumulateAndGet(err, ReadFailure::graver);
                                } else if (opt.isPresent()) {
                                    promise.complete(opt);
                                }
                                return null;
                            }
                        )
                )
                .toArray(CompletableFuture[]::new)
        ).handle(
            (nothing, throwable) -> {
                // "Nobody has it" is only known when everybody answered: a
                // source that failed may hold the blob.
                if (failure.get() == null) {
                    promise.complete(Optional.empty());
                } else {
                    promise.completeExceptionally(failure.get());
                }
                return null;
            }
        );
        return promise;
    }

    /**
     * Read one source; a source that throws instead of returning a failed
     * future counts as failed.
     *
     * @param layer Source
     * @param digest Blob digest
     * @return Blob of the source
     */
    private static CompletableFuture<Optional<Blob>> read(final Layers layer, final Digest digest) {
        CompletableFuture<Optional<Blob>> res;
        try {
            res = layer.get(digest);
        } catch (final RuntimeException ex) {
            res = CompletableFuture.failedFuture(ex);
        }
        return res;
    }
}
