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
package com.auto1.pantera.docker;

import com.auto1.pantera.docker.asto.BlobSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Docker repository files and metadata.
 */
public interface Layers {

    /**
     * Add layer to repository.
     *
     * @param source Blob source.
     * @return Added layer blob.
     */
    CompletableFuture<Digest> put(BlobSource source);

    /**
     * Mount blob to repository.
     *
     * @param blob Blob.
     * @return Mounted blob.
     */
    CompletableFuture<Void> mount(Blob blob);

    /**
     * Find layer by digest.
     *
     * @param digest Layer digest.
     * @return Flow with manifest data, or empty if absent
     */
    CompletableFuture<Optional<Blob>> get(Digest digest);

    /**
     * Delete layer (blob) by digest.
     *
     * <p>GC semantics: blobs are content-addressed and may be referenced by
     * more than one manifest, so this is a separate operation from deleting
     * a manifest reference ({@link Manifests#delete}) — deleting a manifest
     * link never cascades into deleting the blobs it references.
     *
     * @param digest Layer digest.
     * @return Completion signal.
     */
    CompletableFuture<Void> delete(Digest digest);
}
