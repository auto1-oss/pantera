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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read-write {@link Layers} implementation.
 *
 * @since 0.3
 */
public final class ReadWriteLayers implements Layers {

    /**
     * Layers for reading.
     */
    private final Layers read;

    /**
     * Layers for writing.
     */
    private final Layers write;

    /**
     * Ctor.
     *
     * @param read Layers for reading.
     * @param write Layers for writing.
     */
    public ReadWriteLayers(final Layers read, final Layers write) {
        this.read = read;
        this.write = write;
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        return this.write.put(source);
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        return this.write.mount(blob);
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return this.read.get(digest);
    }

    /**
     * Unlike {@link #put}/{@link #mount}, deliberately NOT delegated to
     * {@link #write} — a {@code docker-proxy}'s local tier is a read-through
     * cache, not a user-authoritative store, so it is out of scope for
     * user-initiated delete (WS4-docker.5 §3: "deletes target the
     * authoritative (local) store only" means a standalone {@code docker}
     * repository, not a proxy's cache fill). Maps to 405 via
     * {@code ErrorHandlingSlice}.
     */
    @Override
    public CompletableFuture<Void> delete(final Digest digest) {
        throw new UnsupportedOperationException();
    }
}
