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
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.concurrent.CompletableFuture;

/**
 * Asto {@link Docker} implementation.
 */
public final class AstoDocker implements Docker {

    private final String registryName;

    private final Storage storage;

    /**
     * WS1.7 (spec &sect;3.B2): this hosted registry's presigned-direct-
     * download policy for blob GETs.
     */
    private final DownloadPolicy downloadPolicy;

    /**
     * @param registryName Registry name
     * @param storage Storage
     * @param downloadPolicy WS1.7 presigned-direct-download policy for blob
     *  GETs (see {@link Docker#downloadPolicy()}).
     */
    public AstoDocker(final String registryName, final Storage storage, final DownloadPolicy downloadPolicy) {
        this.registryName = registryName;
        this.storage = storage;
        this.downloadPolicy = downloadPolicy;
    }

    /**
     * Convenience constructor for callers that do not (yet) configure a
     * WS1.7 download policy -- delegates with {@link
     * DownloadPolicy#streamOnly()}, preserving pre-2.3.0 behaviour exactly.
     *
     * @param registryName Registry name
     * @param storage Storage
     */
    public AstoDocker(final String registryName, final Storage storage) {
        this(registryName, storage, DownloadPolicy.streamOnly());
    }

    @Override
    public String registryName() {
        return registryName;
    }

    @Override
    public Repo repo(String name) {
        return new AstoRepo(this.storage, name);
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        final Key root = Layout.repositories();
        return this.storage.list(root).thenApply(keys -> new AstoCatalog(root, keys, pagination));
    }

    @Override
    public DownloadPolicy downloadPolicy() {
        return this.downloadPolicy;
    }
}
