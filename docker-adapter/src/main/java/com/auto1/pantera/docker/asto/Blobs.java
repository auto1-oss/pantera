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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.DockerReferenceNotFoundException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Docker registry blob store.
 */
public final class Blobs {

    private final Storage storage;

    /**
     * @param storage Storage
     */
    public Blobs(Storage storage) {
        this.storage = storage;
    }

    /**
     * Load blob by digest.
     *
     * @param digest Blob digest
     * @return Async publisher output
     */
    public CompletableFuture<Optional<Blob>> blob(Digest digest) {
        final Key key = Layout.blob(digest);
        return storage.exists(key)
            .thenApply(
                exists -> exists
                    ? Optional.of(new AstoBlob(storage, key, digest))
                    : Optional.empty()
        );
    }

    /**
     * Put blob into the store from source.
     *
     * @param source Blob source.
     * @return Added blob.
     */
    public CompletableFuture<Digest> put(BlobSource source) {
        final Digest digest = source.digest();
        final Key key = Layout.blob(digest);
        return source.saveTo(storage, key)
            .thenApply(nothing -> digest);
    }

    /**
     * Delete blob by digest. Content-addressed blobs may be shared by
     * multiple manifests; callers are responsible for confirming a blob
     * is no longer referenced before calling this (registry GC semantics —
     * deleting a manifest reference does not cascade here). Fails (does not
     * silently no-op) when {@code digest} does not resolve to an existing
     * blob, so the HTTP slice can answer {@code 404 BLOB_UNKNOWN} rather
     * than a false {@code 202 Accepted}.
     *
     * @param digest Blob digest.
     * @return Completion signal; fails if the blob does not exist.
     */
    public CompletableFuture<Void> delete(Digest digest) {
        final Key key = Layout.blob(digest);
        return storage.exists(key).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.failedFuture(
                    new DockerReferenceNotFoundException(
                        String.format("blob not found: %s", digest.string())
                    )
                );
            }
            return storage.delete(key);
        });
    }
}
