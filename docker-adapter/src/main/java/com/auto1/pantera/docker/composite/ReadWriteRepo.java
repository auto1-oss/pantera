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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;

/**
 * Read-write {@link Repo} implementation.
 *
 * @since 0.3
 */
public final class ReadWriteRepo implements Repo {

    /**
     * Repository for reading.
     */
    private final Repo read;

    /**
     * Repository for writing.
     */
    private final Repo write;

    /**
     * Ctor.
     *
     * @param read Repository for reading.
     * @param write Repository for writing.
     */
    public ReadWriteRepo(final Repo read, final Repo write) {
        this.read = read;
        this.write = write;
    }

    @Override
    public Layers layers() {
        return new ReadWriteLayers(this.read.layers(), this.write.layers());
    }

    @Override
    public Manifests manifests() {
        return new ReadWriteManifests(this.read.manifests(), this.write.manifests());
    }

    @Override
    public Uploads uploads() {
        return this.write.uploads();
    }
}
