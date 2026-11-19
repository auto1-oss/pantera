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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.nuget.metadata.Nuspec;
import com.auto1.pantera.nuget.metadata.NuspecField;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.json.Json;

/**
 * NuGet repository that stores packages in {@link Storage}.
 *
 * @since 0.5
 */
public final class AstoRepository implements Repository {

    /**
     * The storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage to store all repository data.
     */
    public AstoRepository(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public Storage storage() {
        return this.storage;
    }

    @Override
    public CompletionStage<Optional<Content>> content(final Key key) {
        return this.storage.exists(key).thenCompose(
            exists -> {
                final CompletionStage<Optional<Content>> result;
                if (exists) {
                    result = this.storage.value(key).thenApply(Optional::of);
                } else {
                    result = CompletableFuture.completedFuture(Optional.empty());
                }
                return result;
            }
        );
    }

    /**
     * Add an uploaded package.
     *
     * <p>The "version already published" check and the store run under one
     * exclusive lock on the package root, and the check probes the exact
     * {@code .nupkg} key of the version: a storage {@code list()} is a raw
     * string-prefix scan on S3/in-memory storage, so listing the version root
     * {@code id/1.2.3} would also match {@code id/1.2.30/...} or
     * {@code id/1.2.3-beta/...} and falsely refuse a new version.</p>
     *
     * @param content Package content
     * @param immutable Whether an existing version must be refused
     * @return Stored package info
     */
    @Override
    public CompletionStage<PackageInfo> add(final Content content, final boolean immutable) {
        final Key key = new Key.From(UUID.randomUUID().toString());
        return this.storage.save(key, content).thenCompose(
            saved -> this.storage.value(key)
                .thenCompose(
                    val -> new ContentAsStream<Nuspec>(val).process(
                        input -> new Nupkg(input).nuspec()
                    )
                ).thenCompose(
                    nuspec -> {
                        final PackageIdentity id =
                            new PackageIdentity(nuspec.id(), nuspec.version());
                        return this.storage.exclusively(
                            new PackageKeys(nuspec.id()).rootKey(),
                            target -> target.exists(id.nupkgKey()).thenCompose(
                                exists -> {
                                    final CompletionStage<PackageInfo> res;
                                    if (exists && immutable) {
                                        res = CompletableFuture.failedFuture(
                                            new PackageVersionAlreadyExistsException(
                                                id.toString()
                                            )
                                        );
                                    } else {
                                        res = this.store(target, key, nuspec, id);
                                    }
                                    return res;
                                }
                            )
                        );
                    }
                )
        ).handle(
            (info, err) -> {
                final CompletionStage<PackageInfo> res;
                if (err == null) {
                    res = CompletableFuture.completedFuture(info);
                } else {
                    final Throwable cause;
                    if (err instanceof CompletionException && err.getCause() != null) {
                        cause = err.getCause();
                    } else {
                        cause = err;
                    }
                    res = this.discard(key).thenCompose(
                        nothing -> CompletableFuture.failedFuture(cause)
                    );
                }
                return res;
            }
        ).thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Versions> versions(final PackageKeys id) {
        final Key key = id.versionsKey();
        return this.storage.exists(key).thenCompose(
            exists -> {
                final CompletionStage<Versions> versions;
                if (exists) {
                    versions = this.storage.value(key).thenCompose(
                        val -> new ContentAsStream<Versions>(val)
                            .process(input -> new Versions(Json.createReader(input).readObject()))
                    );
                } else {
                    versions = CompletableFuture.completedFuture(new Versions());
                }
                return versions;
            }
        );
    }

    @Override
    public CompletionStage<Nuspec> nuspec(final PackageIdentity identity) {
        return this.storage.exists(identity.nuspecKey()).thenCompose(
            exists -> {
                if (!exists) {
                    throw new IllegalArgumentException(
                        String.format("Cannot find package: %s", identity)
                    );
                }
                return this.storage.value(identity.nuspecKey())
                    .thenCompose(val -> new ContentAsStream<Nuspec>(val).process(Nuspec.Xml::new));
            }
        );
    }

    /**
     * Delete one package version. The version's files are removed under the
     * package-root lock pushes take, then {@link VersionsPruner} drops the
     * version from {@code index.json} (deleting it when no version is left).
     *
     * @param identity Package identity
     * @return Whether the version existed
     */
    @Override
    public CompletionStage<Boolean> delete(final PackageIdentity identity) {
        return new IndexUpdateLock(this.storage, identity.packageRootKey()).run(
            locked -> locked.exists(identity.nupkgKey()).thenCompose(
                exists -> {
                    final CompletionStage<Boolean> res;
                    if (exists) {
                        res = CompletableFuture.allOf(
                            AstoRepository.deleteIfPresent(locked, identity.nupkgKey()),
                            AstoRepository.deleteIfPresent(locked, identity.nuspecKey()),
                            AstoRepository.deleteIfPresent(locked, identity.hashKey())
                        ).thenApply(nothing -> true);
                    } else {
                        res = CompletableFuture.completedFuture(false);
                    }
                    return res;
                }
            )
        ).thenCompose(
            deleted -> {
                final CompletionStage<Boolean> res;
                if (deleted) {
                    res = new VersionsPruner(this.storage)
                        .afterDelete(identity.nupkgKey().string())
                        .thenApply(pruned -> true);
                } else {
                    res = CompletableFuture.completedFuture(false);
                }
                return res;
            }
        );
    }

    /**
     * Store an uploaded package under its identity: hash, nuspec and nupkg,
     * then record the version in the package's version list. Every key is
     * derived from the package identity, so storing over an existing version
     * replaces each file in place; the version list never gains a duplicate.
     *
     * Must run under the exclusive lock on the package root.
     *
     * @param target Storage handed to the exclusive operation
     * @param key Temporary key holding the upload
     * @param nuspec Package description read from the upload
     * @param id Package identity
     * @return Stored package info
     */
    private CompletionStage<PackageInfo> store(
        final Storage target, final Key key, final Nuspec nuspec, final PackageIdentity id
    ) {
        final PackageKeys pkey = new PackageKeys(nuspec.id());
        return CompletableFuture.allOf(
            this.storage.value(key)
                .thenCompose(val -> new Hash(val).save(target, id)),
            this.storage.save(id.nuspecKey(), new Content.From(nuspec.bytes()))
        )
            .thenCompose(nothing -> target.move(key, id.nupkgKey()))
            .thenCompose(nothing -> this.versions(pkey))
            .thenApply(vers -> AstoRepository.withVersion(vers, nuspec.version()))
            .thenCompose(vers -> vers.save(target, pkey.versionsKey()))
            .thenCompose(
                nothing -> this.storage.metadata(id.nuspecKey())
                    .thenApply(meta -> meta.read(Meta.OP_SIZE).get())
            ).thenApply(
                size -> {
                    final String pkgId = nuspec.id().normalized();
                    com.auto1.pantera.http.cache.NegativeCacheRegistry
                        .instance()
                        .invalidateAfterUpload("nuget", pkgId);
                    com.auto1.pantera.cooldown.metadata
                        .FilteredMetadataCacheRegistry.instance()
                        .invalidateAfterUpload("nuget", pkgId);
                    return new PackageInfo(
                        nuspec.id(), nuspec.version(), size,
                        id.nupkgKey().string()
                    );
                }
            );
    }

    /**
     * Delete the temporary upload key if it is still present (it is gone
     * after a successful move). Cleanup failures are swallowed so the
     * original error reaches the caller.
     *
     * @param key Temporary upload key
     * @return Completion of the cleanup
     */
    private CompletionStage<Void> discard(final Key key) {
        return this.storage.exists(key).thenCompose(
            exists -> {
                final CompletionStage<Void> res;
                if (exists) {
                    res = this.storage.delete(key);
                } else {
                    res = CompletableFuture.completedFuture(null);
                }
                return res;
            }
        ).handle((nothing, ignored) -> null);
    }

    /**
     * Delete a key when it exists.
     *
     * @param target Storage
     * @param key Key
     * @return Completion of the delete
     */
    private static CompletableFuture<Void> deleteIfPresent(final Storage target, final Key key) {
        return target.exists(key).thenCompose(
            exists -> {
                final CompletableFuture<Void> res;
                if (exists) {
                    res = target.delete(key);
                } else {
                    res = CompletableFuture.completedFuture(null);
                }
                return res;
            }
        );
    }

    /**
     * Add a version to the list unless it is already listed.
     *
     * @param versions Current versions
     * @param version Version to add
     * @return Versions listing the version exactly once
     */
    private static Versions withVersion(final Versions versions, final NuspecField version) {
        final String normalized = version.normalized();
        final Versions res;
        if (versions.all().stream().anyMatch(v -> v.normalized().equals(normalized))) {
            res = versions;
        } else {
            res = versions.add(version);
        }
        return res;
    }
}
