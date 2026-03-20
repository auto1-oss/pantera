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
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;

/**
 * Asto implementation of {@link Repo}.
 *
 * @since 0.1
 */
public final class AstoRepo implements Repo {

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * @param asto Asto storage
     * @param name Repository name
     */
    public AstoRepo(Storage asto, String name) {
        this.asto = asto;
        this.name = name;
    }

    @Override
    public Layers layers() {
        return new AstoLayers(this.blobs());
    }

    @Override
    public Manifests manifests() {
        return new AstoManifests(this.asto, this.blobs(), this.name);
    }

    @Override
    public Uploads uploads() {
        return new Uploads(this.asto, this.name);
    }

    /**
     * Get blobs storage.
     *
     * @return Blobs storage.
     */
    private Blobs blobs() {
        return new Blobs(this.asto);
    }
}
