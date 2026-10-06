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
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.VersionExistsException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

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
    /**
     * Lock key below the package: publishes of one package are serialised.
     */
    private static final String LOCK = ".publish.lock";

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
    /**
     * Run a publish under this guard: the check and the write happen inside
     * one storage-backed lock on the package, so two publishes of the same
     * version racing each other are serialised and the second one sees the
     * first one's files and is refused. The lock lives in storage
     * ({@link IndexUpdateLock}), so it also serialises publishers on other
     * instances sharing the storage.
     *
     * @param pkg Package key
     * @param version Version being published, null when unknown
     * @param tarballs Tarball keys the publish writes
     * @param write The write, run only when the version is not published
     * @param <T> Result type
     * @return Result of the write, failed with
     *  {@link com.auto1.pantera.npm.VersionExistsException} when the version
     *  is already published
     */
    <T> CompletableFuture<T> guarded(
        final Key pkg, final String version, final Collection<Key> tarballs,
        final Supplier<CompletionStage<T>> write
    ) {
        if (version == null) {
            return write.get().toCompletableFuture();
        }
        return new IndexUpdateLock(this.storage, new Key.From(pkg, ImmutableVersionGuard.LOCK))
            .run(
                ignored -> this.check(pkg, version, tarballs).thenCompose(checked -> write.get())
            );
    }

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
