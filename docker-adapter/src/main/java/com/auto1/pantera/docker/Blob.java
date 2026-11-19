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

import com.auto1.pantera.asto.Content;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Blob stored in repository.
 *
 * @since 0.2
 */
public interface Blob {

    /**
     * Blob digest.
     *
     * @return Digest.
     */
    Digest digest();

    /**
     * Read blob size.
     *
     * @return Size of blob in bytes.
     */
    CompletableFuture<Long> size();

    /**
     * Read blob content.
     *
     * @return Content.
     */
    CompletableFuture<Content> content();

    /**
     * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2): a presigned
     * direct-download URL for this blob's bytes, if the backing storage
     * currently supports issuing one (the object is durably confirmed in
     * the blob store AND the backend has presign configured -- see {@code
     * com.auto1.pantera.asto.blob.PresignResolver}). Default empty: {@link
     * Blob} implementations not backed by a presign-capable {@code Storage}
     * (e.g. an upstream-registry-backed proxy blob) need not override this;
     * their callers simply fall back to {@link #content()} streaming,
     * exactly as before WS1.7.
     *
     * <p>This method performs only the TECHNICAL "can I presign this object
     * right now" check -- callers MUST additionally gate the attempt behind
     * the repository's configured {@code download-mode} ({@link
     * Docker#downloadPolicy()}); this method never checks that itself, so a
     * {@code stream}-mode repo's serving slice must not call it at all.</p>
     *
     * @param ttlSeconds Presigned URL validity window, in seconds.
     * @return The presigned URL, or empty if a redirect is not currently
     *  possible (no presigner configured for the backend, or the object is
     *  not yet durably present in the blob store).
     */
    default Optional<URI> presignedUrl(final long ttlSeconds) {
        return Optional.empty();
    }
}
