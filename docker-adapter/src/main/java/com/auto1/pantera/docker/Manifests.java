/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.auto1.pantera.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Docker repository manifests.
 */
public interface Manifests {

    /**
     * Put manifest.
     *
     * @param ref     Manifest reference.
     * @param content Manifest content.
     * @return Added manifest.
     */
    CompletableFuture<Manifest> put(ManifestReference ref, Content content);

    /**
     * Put manifest without validating that referenced blobs exist.
     * Used by cache implementations where blobs may be lazily cached.
     *
     * @param ref     Manifest reference.
     * @param content Manifest content.
     * @return Added manifest.
     */
    default CompletableFuture<Manifest> putUnchecked(ManifestReference ref, Content content) {
        return put(ref, content);
    }

    /**
     * Get manifest by reference.
     *
     * @param ref Manifest reference
     * @return Manifest instance if it is found, empty if manifest is absent.
     */
    CompletableFuture<Optional<Manifest>> get(ManifestReference ref);

    /**
     * List manifest tags.
     *
     * @param pagination  Pagination parameters.
     * @return Tags.
     */
    CompletableFuture<Tags> tags(Pagination pagination);
}
