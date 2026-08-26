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
     * Get manifest by reference, honoring the client's negotiated
     * {@code Accept}-variant (WS4-docker.7).
     *
     * <p>Only the proxy/cache implementations care: they forward the variant's
     * {@code Accept} upstream and key the cache by {@link ManifestVariant#cacheToken()}
     * so a v2-manifest and an OCI-index representation of the same tag are
     * fetched and cached independently, never cross-served. Every other
     * implementation (authoritative local store, group, composites) serves a
     * single stored representation and ignores the variant — the default here
     * delegates to {@link #get(ManifestReference)} so they need no change.</p>
     *
     * @param ref Manifest reference.
     * @param variant Negotiated {@code Accept}-variant.
     * @return Manifest instance if it is found, empty if manifest is absent.
     */
    default CompletableFuture<Optional<Manifest>> get(
        final ManifestReference ref, final ManifestVariant variant
    ) {
        return get(ref);
    }

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

    /**
     * Delete manifest by reference (tag or digest).
     *
     * <p>Removes the link {@code ref} resolves to, and — when {@code ref}
     * is not itself the canonical by-digest reference — also the by-digest
     * link, so nothing is left pointing at a manifest with no remaining
     * named reference. Other tags that independently reference the same
     * digest are unaffected (each tag's own link is a separate key). If the
     * deleted manifest carried an OCI 1.1 {@code subject}, its referrers-index
     * entry is pruned too, so it stops appearing in {@code oras discover}.
     *
     * <p>Fails (does not silently no-op) when {@code ref} does not resolve
     * to an existing manifest, so the HTTP slice can answer {@code 404
     * MANIFEST_UNKNOWN} rather than a false {@code 202 Accepted}.
     *
     * <p>Does not cascade into deleting the underlying blob — blob GC is
     * {@link Layers#delete}, a separate operation, since content-addressed
     * blobs may be referenced by more than one manifest.
     *
     * @param ref Manifest reference to delete.
     * @return Completion signal; fails if {@code ref} does not resolve to
     *         an existing manifest.
     */
    CompletableFuture<Void> delete(ManifestReference ref);
}
