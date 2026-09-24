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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Removes the releases whose tarball is no longer in storage from a local
 * Hex repository's package registry ({@code packages/<name>}) after a
 * management-API delete.
 *
 * <p>The registry is written on publish and served as stored, so a release
 * whose {@code tarballs/<name>-<version>.tar} was deleted stayed listed and
 * {@code mix deps.get} failed downloading it. A release is kept while its
 * tarball is stored; a registry left with no release is removed. Since a
 * tarball name does not say where the package name ends (both can contain
 * dashes), every package the name can belong to is checked. The rewrite
 * holds the registry lock the publish takes.</p>
 *
 * @since 2.2.9
 */
public final class ReleasesPruner {

    /**
     * Tarball extension.
     */
    private static final String TAR = ".tar";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public ReleasesPruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Prune the registries a deleted path can be listed in.
     * @param deleted Deleted storage path, repository-relative
     * @return Names of the packages whose releases were removed
     */
    public CompletableFuture<Set<String>> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        final String prefix = DownloadSlice.TARBALLS + '/';
        final CompletableFuture<Collection<String>> names;
        if (clean.startsWith(prefix) && clean.endsWith(ReleasesPruner.TAR)) {
            final String file = clean.substring(
                prefix.length(), clean.length() - ReleasesPruner.TAR.length()
            );
            final Set<String> candidates = new LinkedHashSet<>();
            for (int idx = file.indexOf('-'); idx > 0; idx = file.indexOf('-', idx + 1)) {
                candidates.add(file.substring(0, idx));
            }
            names = CompletableFuture.completedFuture(candidates);
        } else if (DownloadSlice.TARBALLS.equals(clean)) {
            names = this.storage.list(new Key.From(DownloadSlice.PACKAGES)).thenApply(
                keys -> keys.stream()
                    .map(key -> key.string().substring(DownloadSlice.PACKAGES.length() + 1))
                    .filter(name -> name.indexOf('/') < 0)
                    .collect(Collectors.toList())
            );
        } else {
            names = CompletableFuture.completedFuture(List.of());
        }
        return names.thenCompose(
            list -> {
                final Set<String> changed = new HashSet<>();
                CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
                for (final String name : list) {
                    res = res.thenCompose(
                        nothing -> this.prune(name).thenAccept(
                            pruned -> {
                                if (pruned) {
                                    changed.add(name);
                                }
                            }
                        )
                    );
                }
                return res.thenApply(nothing -> changed);
            }
        );
    }

    /**
     * Prune one package registry under its lock.
     * @param name Package name
     * @return Whether releases were removed
     */
    private CompletableFuture<Boolean> prune(final String name) {
        final Key registry = new Key.From(DownloadSlice.PACKAGES, name);
        return this.storage.exists(registry).thenCompose(
            present -> {
                if (!present) {
                    return CompletableFuture.completedFuture(false);
                }
                return new IndexUpdateLock(this.storage, registry).run(
                    locked -> locked.exists(registry).thenCompose(
                        still -> {
                            if (!still) {
                                return CompletableFuture.completedFuture(false);
                            }
                            return locked.value(registry)
                                .thenCompose(Content::asBytesFuture)
                                .thenCompose(bytes -> this.rewrite(locked, registry, name, bytes));
                        }
                    )
                );
            }
        );
    }

    /**
     * Rewrite a registry with the releases whose tarball is stored.
     * @param target Storage
     * @param registry Registry key
     * @param name Package name
     * @param gzipped Stored registry
     * @return Whether releases were removed
     */
    private CompletableFuture<Boolean> rewrite(
        final Storage target, final Key registry, final String name, final byte[] gzipped
    ) {
        final PackageOuterClass.Package pkg;
        try {
            pkg = PackageOuterClass.Package.parseFrom(
                SignedOuterClass.Signed.parseFrom(new Gzip(gzipped).decompress()).getPayload()
            );
        } catch (final InvalidProtocolBufferException ex) {
            return CompletableFuture.failedFuture(new PanteraException("Cannot parse package", ex));
        }
        final List<PackageOuterClass.Release> releases = pkg.getReleasesList();
        final List<CompletableFuture<Boolean>> stored = new ArrayList<>(releases.size());
        for (final PackageOuterClass.Release release : releases) {
            stored.add(
                target.exists(
                    new Key.From(
                        DownloadSlice.TARBALLS,
                        String.format("%s-%s%s", name, release.getVersion(), ReleasesPruner.TAR)
                    )
                )
            );
        }
        return CompletableFuture.allOf(stored.toArray(new CompletableFuture[0])).thenCompose(
            nothing -> {
                final List<PackageOuterClass.Release> kept = new ArrayList<>(releases.size());
                for (int idx = 0; idx < releases.size(); idx += 1) {
                    if (stored.get(idx).join()) {
                        kept.add(releases.get(idx));
                    }
                }
                final CompletableFuture<Boolean> res;
                if (kept.size() == releases.size()) {
                    res = CompletableFuture.completedFuture(false);
                } else if (kept.isEmpty()) {
                    res = target.delete(registry).thenApply(deleted -> true);
                } else {
                    final SignedOuterClass.Signed signed = SignedOuterClass.Signed.newBuilder()
                        .setPayload(
                            ByteString.copyFrom(
                                pkg.toBuilder().clearReleases().addAllReleases(kept).build()
                                    .toByteArray()
                            )
                        )
                        .setSignature(ByteString.EMPTY)
                        .build();
                    res = target.save(
                        registry, new Content.From(new Gzip(signed.toByteArray()).compress())
                    ).thenApply(saved -> true);
                }
                return res;
            }
        );
    }
}
