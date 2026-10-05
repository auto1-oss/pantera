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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.VersionExistsException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Publish guard of an immutable npm repository: refuses a publish that
 * would overwrite a stored version, i.e. when the per-version metadata file
 * of the version being published, or one of the tarballs the publish
 * writes, is already stored. Identical re-publishes are refused too (npm
 * registry semantics: a published version can never be published again).
 *
 * <p>Only what the publish actually writes is checked, so a payload that
 * lists other, already published versions next to the new one is not
 * refused.</p>
 *
 * @since 2.2.10
 */
final class ImmutableVersionGuard {

    /**
     * Storage holding the packages.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Storage holding the packages
     */
    ImmutableVersionGuard(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Check that a publish overwrites nothing.
     * @param pkg Package key
     * @param version Version the publish writes, {@code null} when unknown
     *  (left to the publish itself to reject)
     * @param tarballs Tarball keys the publish writes
     * @return Completion, failed with {@link VersionExistsException} when the
     *  version is already published
     */
    CompletableFuture<Void> check(
        final Key pkg, final String version, final Collection<Key> tarballs
    ) {
        if (version == null) {
            return CompletableFuture.completedFuture(null);
        }
        return new PerVersionLayout(this.storage).hasVersion(pkg, version)
            .toCompletableFuture()
            .thenCompose(
                exists -> {
                    if (exists) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return this.anyExists(tarballs);
                }
            ).thenAccept(
                taken -> {
                    if (taken) {
                        throw new VersionExistsException(version);
                    }
                }
            );
    }

    /**
     * Whether any of the keys is stored.
     * @param keys Keys
     * @return True when at least one is stored
     */
    private CompletableFuture<Boolean> anyExists(final Collection<Key> keys) {
        final List<CompletableFuture<Boolean>> checks = keys.stream()
            .map(this.storage::exists)
            .toList();
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
            .thenApply(
                ignored -> checks.stream().anyMatch(CompletableFuture::join)
            );
    }
}
