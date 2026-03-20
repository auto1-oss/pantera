/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read-write {@link Manifests} implementation.
 */
public final class ReadWriteManifests implements Manifests {

    /**
     * Manifests for reading.
     */
    private final Manifests read;

    /**
     * Manifests for writing.
     */
    private final Manifests write;

    /**
     * @param read Manifests for reading.
     * @param write Manifests for writing.
     */
    public ReadWriteManifests(final Manifests read, final Manifests write) {
        this.read = read;
        this.write = write;
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
        return this.write.put(ref, content);
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        return this.read.get(ref);
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        return this.read.tags(pagination);
    }
}
