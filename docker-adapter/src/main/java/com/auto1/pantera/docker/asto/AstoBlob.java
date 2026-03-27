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
import com.auto1.pantera.asto.MetaCommon;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;

import java.util.concurrent.CompletableFuture;

/**
 * Asto implementation of {@link Blob}.
 */
public final class AstoBlob implements Blob {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Blob key.
     */
    private final Key key;

    /**
     * Blob digest.
     */
    private final Digest digest;

    /**
     * @param storage Storage.
     * @param key Blob key.
     * @param digest Blob digest.
     */
    public AstoBlob(Storage storage, Key key, Digest digest) {
        this.storage = storage;
        this.key = key;
        this.digest = digest;
    }

    @Override
    public Digest digest() {
        return this.digest;
    }

    @Override
    public CompletableFuture<Long> size() {
        return this.storage.metadata(this.key)
            .thenApply(meta -> new MetaCommon(meta).size());
    }

    @Override
    public CompletableFuture<Content> content() {
        // Storage.value() already returns Content with size, no need to wrap
        return this.storage.value(this.key);
    }
}
