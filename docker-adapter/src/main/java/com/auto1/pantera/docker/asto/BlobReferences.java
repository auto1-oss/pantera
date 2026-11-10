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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Which images (repository names) of one registry reference a blob.
 *
 * <p>Blobs live in a registry-wide content-addressed store
 * ({@code blobs/<alg>/<xx>/<hex>/data}); there are no per-image layer links.
 * An image references a blob when one of its manifests — every
 * {@code repositories/<name>/_manifests/revisions/<alg>/<hex>/link}, which a
 * manifest push always writes and a manifest delete removes together with
 * its tags — either <em>is</em> that blob or mentions its digest (config,
 * layers, manifest-list children, subject, annotations).</p>
 *
 * <p>The mention check is a literal search for the digest string in the
 * manifest bytes rather than a structural parse. That is deliberately
 * conservative: it covers every manifest schema (schema1 {@code blobSum},
 * OCI index, foreign layers, referrers) and can only over-report a
 * reference, never miss one. An unreadable manifest of another image also
 * counts as a reference, so a blob is never classified as unshared on
 * incomplete evidence.</p>
 *
 * <p>OCI 1.1 referrers (signatures, SBOMs, attestations) are pushed as
 * ordinary manifests and get the same revision link, so their config,
 * layers and {@code subject} count like any other manifest's — also once
 * their tag is gone, since a tag delete keeps the manifest pullable by
 * digest. The referrers-index entries under {@code _manifests/referrers/}
 * are descriptors of those manifests, not manifests, and are not read.</p>
 *
 * <p>Cost: one recursive listing of {@code repositories/} (manifest digests
 * are taken from the key path, no link reads) plus reads of the candidate
 * manifests, {@value #BATCH} at a time, stopping at the first match.</p>
 */
final class BlobReferences {

    /**
     * Manifests read concurrently per batch.
     */
    private static final int BATCH = 16;

    /**
     * Segment holding an image's manifests.
     */
    private static final String MANIFESTS = "_manifests";

    /**
     * Segment holding by-digest manifest links.
     */
    private static final String REVISIONS = "revisions";

    /**
     * Link file name.
     */
    private static final String LINK = "link";

    /**
     * Registry storage.
     */
    private final Storage storage;

    /**
     * Blob store of the same storage.
     */
    private final Blobs blobs;

    /**
     * Ctor.
     *
     * @param storage Registry storage.
     */
    BlobReferences(final Storage storage) {
        this.storage = storage;
        this.blobs = new Blobs(storage);
    }

    /**
     * Classifies {@code digest} relative to image {@code name}.
     *
     * @param name Image (repository) name.
     * @param digest Blob digest.
     * @return Usage of the blob by {@code name} and by every other image.
     */
    CompletableFuture<Usage> usage(final String name, final Digest digest) {
        return this.storage.list(Layout.repositories()).thenCompose(
            keys -> {
                final Map<String, Set<String>> revisions = revisions(keys);
                final Set<String> own = revisions.getOrDefault(name, Set.of());
                final Set<String> others = new LinkedHashSet<>();
                revisions.forEach(
                    (image, digests) -> {
                        if (!image.equals(name)) {
                            others.addAll(digests);
                        }
                    }
                );
                return this.referenced(own, digest, false).thenCompose(
                    owned -> {
                        if (!owned) {
                            return CompletableFuture.completedFuture(Usage.NOT_REFERENCED);
                        }
                        return this.referenced(others, digest, true).thenApply(
                            shared -> shared ? Usage.SHARED : Usage.EXCLUSIVE
                        );
                    }
                );
            }
        );
    }

    /**
     * Whether any of {@code manifests} is or mentions {@code digest}.
     *
     * @param manifests Manifest digest strings.
     * @param digest Blob digest.
     * @param strict Treat an unreadable manifest as a reference.
     * @return True when referenced.
     */
    private CompletableFuture<Boolean> referenced(
        final Collection<String> manifests, final Digest digest, final boolean strict
    ) {
        if (manifests.contains(digest.string())) {
            return CompletableFuture.completedFuture(true);
        }
        return this.scan(new ArrayList<>(manifests), 0, digest, strict);
    }

    /**
     * Reads manifests batch by batch from {@code from}, stopping at the first
     * one that mentions {@code digest}.
     *
     * @param manifests Manifest digest strings.
     * @param from Index of the first manifest of this batch.
     * @param digest Blob digest.
     * @param strict Treat an unreadable manifest as a reference.
     * @return True when referenced.
     */
    private CompletableFuture<Boolean> scan(
        final List<String> manifests, final int from, final Digest digest, final boolean strict
    ) {
        if (from >= manifests.size()) {
            return CompletableFuture.completedFuture(false);
        }
        final List<CompletableFuture<Boolean>> batch = manifests
            .subList(from, Math.min(from + BATCH, manifests.size()))
            .stream()
            .map(manifest -> this.mentions(new Digest.FromString(manifest), digest, strict))
            .toList();
        return CompletableFuture.allOf(batch.toArray(new CompletableFuture<?>[0])).thenCompose(
            nothing -> {
                // Every future is complete here: join() does not block.
                if (batch.stream().anyMatch(CompletableFuture::join)) {
                    return CompletableFuture.completedFuture(true);
                }
                return this.scan(manifests, from + BATCH, digest, strict);
            }
        );
    }

    /**
     * Whether manifest {@code manifest} mentions {@code digest}. A manifest
     * whose blob is gone references nothing; a manifest that cannot be read
     * or decoded counts as a reference when {@code strict}.
     *
     * @param manifest Manifest digest.
     * @param digest Blob digest.
     * @param strict Treat an unreadable manifest as a reference.
     * @return True when mentioned.
     */
    private CompletableFuture<Boolean> mentions(
        final Digest.FromString manifest, final Digest digest, final boolean strict
    ) {
        if (!manifest.valid()) {
            return CompletableFuture.completedFuture(strict);
        }
        return this.blobs.blob(manifest).thenCompose(
            found -> found.map(
                blob -> blob.content()
                    .thenCompose(Content::asBytesFuture)
                    .thenApply(
                        bytes -> new String(bytes, StandardCharsets.UTF_8)
                            .contains(digest.string())
                    )
            ).orElseGet(() -> CompletableFuture.completedFuture(false))
        ).exceptionally(err -> strict);
    }

    /**
     * Manifest digests per image, from the revision-link keys.
     *
     * @param keys Every key under {@code repositories/}.
     * @return Image name to manifest digest strings.
     */
    private static Map<String, Set<String>> revisions(final Collection<Key> keys) {
        final Map<String, Set<String>> res = new HashMap<>();
        for (final Key key : keys) {
            final List<String> parts = key.parts();
            final int idx = parts.indexOf(MANIFESTS);
            if (idx > 1 && parts.size() == idx + 5
                && REVISIONS.equals(parts.get(idx + 1))
                && LINK.equals(parts.get(idx + 4))) {
                res.computeIfAbsent(
                    String.join("/", parts.subList(1, idx)), image -> new LinkedHashSet<>()
                ).add(String.format("%s:%s", parts.get(idx + 2), parts.get(idx + 3)));
            }
        }
        return res;
    }

    /**
     * How a blob is referenced, from the point of view of one image.
     */
    enum Usage {
        /**
         * No manifest of the image references the blob.
         */
        NOT_REFERENCED,

        /**
         * Referenced by the image and by no other image.
         */
        EXCLUSIVE,

        /**
         * Referenced by the image and by at least one other image (or the
         * evidence about another image is incomplete).
         */
        SHARED
    }
}
