/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.fake;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Manifests implementation that contains manifest.
 */
public final class FullGetManifests implements Manifests {

    /**
     * Digest hex of manifest.
     */
    private final String hex;

    /**
     * Manifest content.
     */
    private final String content;

    /**
     * Ctor.
     *
     * @param hex Digest hex of manifest.
     */
    public FullGetManifests(final String hex) {
        this(hex, "{ \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\", \"schemaVersion\": 2 }");
    }

    /**
     * Ctor.
     *
     * @param hex Digest hex of manifest.
     * @param content Manifest content.
     */
    public FullGetManifests(final String hex, final String content) {
        this.hex = hex;
        this.content = content;
    }

    @Override
    public CompletableFuture<Manifest> put(final ManifestReference ref, final Content ignored) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        return CompletableFuture.completedFuture(
            Optional.of(
                new Manifest(
                    new Digest.Sha256(this.hex),
                    this.content.getBytes()
                )
            )
        );
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        throw new UnsupportedOperationException();
    }
}
