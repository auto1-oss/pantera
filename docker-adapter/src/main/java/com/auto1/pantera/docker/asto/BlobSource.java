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
import com.auto1.pantera.docker.Digest;

import java.util.concurrent.CompletableFuture;

/**
 * Source of blob that could be saved to {@link Storage} at desired location.
 *
 * @since 0.12
 */
public interface BlobSource {

    /**
     * Blob digest.
     *
     * @return Digest.
     */
    Digest digest();

    /**
     * Save blob to storage.
     *
     * @param storage Storage.
     * @param key     Destination for blob content.
     * @return Completion of save operation.
     */
    CompletableFuture<Void> saveTo(Storage storage, Key key);
}
