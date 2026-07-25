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
package com.auto1.pantera.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.Referrers;
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

    /**
     * OCI 1.1 referrers of a subject digest — every manifest indexed with
     * a {@code subject} pointing at {@code subject} on push.
     *
     * <p>Default is an always-empty listing: only a hosted (authoritative)
     * store indexes referrers on push, so this is the correct answer for
     * proxy/cache/composite implementations (proxy-through of upstream
     * referrers is out of scope for 2.3.0 — see WS4-docker.2 §3).
     *
     * @param subject Subject digest to look up referrers for.
     * @param artifactType When present, narrows the listing to referrers
     *                     whose {@code artifactType} equals this value.
     * @return Referrers listing, possibly empty.
     */
    default CompletableFuture<Referrers> referrers(Digest subject, Optional<String> artifactType) {
        return CompletableFuture.completedFuture(Referrers.EMPTY);
    }
}
