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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.error.BlobInUseException;
import com.auto1.pantera.docker.error.DockerReferenceNotFoundException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asto implementation of {@link Layers} for one image of a registry.
 *
 * <p>Blob data is registry-wide and content-addressed; reads, uploads and
 * mounts go straight to that shared store. Deletion is scoped to the image:
 * see {@link #delete(Digest)}.</p>
 */
public final class AstoLayers implements Layers {

    /**
     * Blobs storage.
     */
    private final Blobs blobs;

    /**
     * Cross-image blob reference lookup.
     */
    private final BlobReferences references;

    /**
     * Image (repository) name these layers belong to.
     */
    private final String name;

    /**
     * @param storage Registry storage.
     * @param name Image (repository) name.
     */
    public AstoLayers(final Storage storage, final String name) {
        this.blobs = new Blobs(storage);
        this.references = new BlobReferences(storage);
        this.name = name;
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

    /**
     * Deletes a blob on behalf of this image. Blob data is shared by every
     * image of the registry and there are no per-image layer links, so:
     * <ul>
     *   <li>a digest that no manifest of this image is or references fails
     *       with {@link DockerReferenceNotFoundException} (404
     *       {@code BLOB_UNKNOWN}) — even when the blob exists for another
     *       image, so delete permission on one image never reaches another
     *       image's blobs;</li>
     *   <li>a digest also referenced by a manifest of any other image (or
     *       whose sharing cannot be ruled out because another image's
     *       manifest is unreadable) fails with {@link BlobInUseException}
     *       (409) and nothing is removed;</li>
     *   <li>otherwise the blob data is removed. This image's manifests are
     *       left in place (no cascade, as with any registry blob GC).</li>
     * </ul>
     * Blobs uploaded but not yet referenced by any manifest cannot be
     * attributed to an image and are therefore never deletable here. The
     * check-then-delete is not atomic against a concurrent push of a
     * manifest that references the same blob from another image; such a
     * push then fails validation and must re-upload the blob.
     *
     * @param digest Blob digest.
     * @return Completion signal.
     */
    @Override
    public CompletableFuture<Void> delete(final Digest digest) {
        return this.references.usage(this.name, digest).thenCompose(
            usage -> switch (usage) {
                case NOT_REFERENCED -> CompletableFuture.failedFuture(
                    new DockerReferenceNotFoundException(
                        String.format(
                            "blob %s is not referenced by %s", digest.string(), this.name
                        )
                    )
                );
                case SHARED -> CompletableFuture.failedFuture(
                    new BlobInUseException(
                        String.format(
                            "blob %s is referenced by another repository", digest.string()
                        )
                    )
                );
                case EXCLUSIVE -> this.blobs.delete(digest);
            }
        );
    }
}
