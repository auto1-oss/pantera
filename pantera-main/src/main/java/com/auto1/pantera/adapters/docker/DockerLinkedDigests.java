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
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.cooldown.CooldownLinkedVersions;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.OfficialImageName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Digests a docker cooldown unblock releases together with the named
 * version, read from the proxy's cached manifests.
 *
 * <p>Why tag and digest rows must be released together: a pull of
 * {@code image:tag} is evaluated under the tag and under the manifest digest
 * it resolves to, and a multi-arch tag is completed by digest-addressed
 * pulls of the index's child manifests, each evaluated on its own. Of the
 * three designs — (a) evaluate digests against the decision of a tag that
 * references them, (b) release the digests reachable from a tag when the tag
 * is unblocked, (c) stop keying digests reached via a tag — only (b) keeps
 * both guarantees: (a) needs a digest→tag index that survives restarts and
 * spans instances, and (a)/(c) would let a tag that upstream later moves to
 * fresh content inherit the old unblock. (b) snapshots what the tag points
 * at when the operator unblocks it: the cached tag link gives the manifest
 * digest, and an index lists its children. Nothing else is returned, so an
 * unrelated fresh digest of the same image stays blocked.</p>
 *
 * <p>The cache holds an image under the client's spelling ({@code nginx} or
 * {@code library/nginx}), so both spellings of the canonical cooldown name
 * are tried.</p>
 *
 * @since 2.2.9
 */
final class DockerLinkedDigests implements CooldownLinkedVersions.Resolver {

    /**
     * Cache storage view (never contacts the upstream).
     */
    private final Docker cache;

    /**
     * Official-image naming rule of the upstream.
     */
    private final OfficialImageName names;

    /**
     * Ctor.
     *
     * @param cache Docker over the proxy's cache storage
     * @param names Official-image naming rule of the upstream
     */
    DockerLinkedDigests(final Docker cache, final OfficialImageName names) {
        this.cache = cache;
        this.names = names;
    }

    @Override
    public CompletableFuture<List<String>> linked(final String artifact, final String version) {
        final ManifestReference ref;
        try {
            ref = ManifestReference.from(version);
        } catch (final IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(List.of());
        }
        final boolean tag = !new Digest.FromString(version).valid();
        return this.manifest(artifact, ref).thenCompose(first -> {
            if (first.isPresent()) {
                return CompletableFuture.completedFuture(first);
            }
            final String alias = this.names.alias(artifact);
            if (alias.equals(artifact)) {
                return CompletableFuture.completedFuture(first);
            }
            return this.manifest(alias, ref);
        }).thenApply(found -> found.map(doc -> reachable(doc, tag)).orElse(List.of()));
    }

    /**
     * Cached manifest for a name, empty when absent or unreadable.
     */
    private CompletableFuture<Optional<Manifest>> manifest(
        final String name, final ManifestReference ref
    ) {
        try {
            return this.cache.repo(name).manifests().get(ref)
                .exceptionally(err -> Optional.empty());
        } catch (final IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Digests reachable from a manifest: its own digest when it was reached
     * through a tag, plus the child manifests of an index.
     */
    private static List<String> reachable(final Manifest doc, final boolean tag) {
        final List<String> out = new ArrayList<>();
        if (tag) {
            out.add(doc.digest().string());
        }
        if (doc.isManifestList()) {
            doc.manifestListChildren().forEach(child -> out.add(child.string()));
        }
        return out;
    }
}
