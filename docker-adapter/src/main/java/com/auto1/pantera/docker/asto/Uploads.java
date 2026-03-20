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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Docker repository files and metadata.
 */
public final class Uploads {

    private final Storage storage;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * @param storage Asto storage
     * @param name Repository name
     */
    public Uploads(Storage storage, String name) {
        this.storage = storage;
        this.name = name;
    }

    /**
     * Start new upload.
     *
     * @return Upload.
     */
    public CompletableFuture<Upload> start() {
        final String uuid = UUID.randomUUID().toString();
        final Upload upload = new Upload(this.storage, this.name, uuid);
        return upload.start().thenApply(ignored -> upload);
    }

    /**
     * Find upload by UUID.
     *
     * @param uuid Upload UUID.
     * @return Upload.
     */
    public CompletableFuture<Optional<Upload>> get(final String uuid) {
        if (uuid.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.storage.list(Layout.upload(this.name, uuid)).thenApply(
                list -> {
                    if (list.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Upload(this.storage, this.name, uuid));
                }
            );
    }
}
