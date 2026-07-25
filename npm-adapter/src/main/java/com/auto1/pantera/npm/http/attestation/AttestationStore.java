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
package com.auto1.pantera.npm.http.attestation;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage-backed sidecar for {@code npm publish --provenance} attestation
 * bundles — a durable {@code <pkg>/-/attestations/<name>-<version>.sigstore.json}
 * file per published version, mirroring the dist-tags sidecar pattern
 * (WS4-npm.3) rather than a new DB table.
 *
 * <p>Stores the bundle exactly as received (never mis-routed into the
 * tarball path, never silently dropped) so {@code GET
 * /-/npm/v1/attestations/&lt;spec&gt;} can serve it back faithfully.</p>
 *
 * @since 2.3.0
 */
public final class AttestationStore {

    /**
     * Storage backing this repository.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage backing this repository
     */
    public AttestationStore(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Persist an attestation/provenance bundle for a published version.
     *
     * @param name Package name
     * @param version Version being published
     * @param bundle Raw bundle bytes as received in the publish payload
     * @return Completion stage
     */
    public CompletableFuture<Void> store(final String name, final String version, final byte[] bundle) {
        return this.storage.save(this.key(name, version), new Content.From(bundle)).toCompletableFuture();
    }

    /**
     * Read a previously stored attestation bundle.
     *
     * @param name Package name
     * @param version Version
     * @return Completion stage with the raw bundle bytes, or empty when none was stored
     */
    public CompletableFuture<Optional<byte[]>> read(final String name, final String version) {
        final Key key = this.key(name, version);
        return this.storage.exists(key).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(Optional.<byte[]>empty());
            }
            return this.storage.value(key).thenCompose(Content::asBytesFuture).thenApply(Optional::of);
        });
    }

    private Key key(final String name, final String version) {
        return new Key.From(
            name, "-", "attestations",
            String.format("%s-%s.sigstore.json", AttestationStore.sanitize(name), version)
        );
    }

    private static String sanitize(final String name) {
        return name.replace("/", "-").replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
