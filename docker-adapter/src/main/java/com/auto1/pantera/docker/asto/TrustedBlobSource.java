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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;

import java.util.concurrent.CompletableFuture;

/**
 * BlobSource which content is trusted and does not require digest validation.
 */
public final class TrustedBlobSource implements BlobSource {

    /**
     * Blob digest.
     */
    private final Digest dig;

    /**
     * Blob content.
     */
    private final Content content;

    /**
     * @param bytes Blob bytes.
     */
    public TrustedBlobSource(final byte[] bytes) {
        this(new Content.From(bytes), new Digest.Sha256(bytes));
    }

    /**
     * @param content Blob content.
     * @param dig Blob digest.
     */
    public TrustedBlobSource(final Content content, final Digest dig) {
        this.dig = dig;
        this.content = content;
    }

    @Override
    public Digest digest() {
        return this.dig;
    }

    @Override
    public CompletableFuture<Void> saveTo(Storage storage, Key key) {
        return storage.exists(key)
            .thenCompose(
                exists -> exists ? CompletableFuture.completedFuture(null)
                    : storage.save(key, content)
        );
    }
}
