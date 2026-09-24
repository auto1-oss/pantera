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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.nuget.metadata.PackageId;
import com.auto1.pantera.nuget.metadata.Version;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonString;

/**
 * Removes the versions whose package is no longer in storage from a local
 * NuGet package's version registry ({@code <id>/index.json}) after a
 * management-API delete.
 *
 * <p>The registry is written on push and never shrinks, so a version whose
 * {@code .nupkg} or {@code .nuspec} was deleted stayed listed: the
 * flat-container version list offered it and the registration page failed
 * reading its missing {@code .nuspec}. A version is kept while both its
 * {@code .nupkg} and {@code .nuspec} are stored; a registry left with no
 * version is removed. The rewrite holds the package lock the push takes.</p>
 *
 * @since 2.2.9
 */
public final class VersionsPruner {

    /**
     * Versions array name.
     */
    private static final String VERSIONS = "versions";

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Repository storage
     */
    public VersionsPruner(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Prune the registry of the package a deleted path belonged to.
     * @param deleted Deleted storage path, repository-relative
     * @return Normalized id of the package whose registry changed, or null
     */
    public CompletableFuture<String> afterDelete(final String deleted) {
        String clean = deleted.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        final int slash = clean.indexOf('/');
        final CompletableFuture<String> res;
        if (slash <= 0) {
            res = CompletableFuture.completedFuture(null);
        } else {
            final PackageKeys pkg = new PackageKeys(clean.substring(0, slash));
            res = new IndexUpdateLock(this.storage, pkg.rootKey()).run(
                locked -> locked.exists(pkg.versionsKey()).thenCompose(
                    exists -> {
                        if (!exists) {
                            return CompletableFuture.<String>completedFuture(null);
                        }
                        return locked.value(pkg.versionsKey())
                            .thenCompose(Content::asJsonObjectFuture)
                            .thenCompose(json -> this.rewrite(locked, pkg, json));
                    }
                )
            );
        }
        return res;
    }

    /**
     * Rewrite the registry with the versions still stored.
     * @param target Storage
     * @param pkg Package keys
     * @param json Registry
     * @return Normalized package id when the registry changed, otherwise null
     */
    private CompletableFuture<String> rewrite(
        final Storage target, final PackageKeys pkg, final JsonObject json
    ) {
        final List<String> listed = new ArrayList<>(0);
        if (json.containsKey(VersionsPruner.VERSIONS)) {
            json.getJsonArray(VersionsPruner.VERSIONS).getValuesAs(JsonString.class)
                .forEach(value -> listed.add(value.getString()));
        }
        final PackageId id = new PackageId(pkg.rootKey().string());
        final List<CompletableFuture<Boolean>> present = new ArrayList<>(listed.size());
        for (final String version : listed) {
            final PackageIdentity identity = new PackageIdentity(id, new Version(version));
            present.add(
                target.exists(identity.nupkgKey()).thenCombine(
                    target.exists(identity.nuspecKey()), (nupkg, nuspec) -> nupkg && nuspec
                )
            );
        }
        return CompletableFuture.allOf(present.toArray(new CompletableFuture[0])).thenCompose(
            nothing -> {
                final JsonArrayBuilder kept = Json.createArrayBuilder();
                int count = 0;
                for (int idx = 0; idx < listed.size(); idx += 1) {
                    if (present.get(idx).join()) {
                        kept.add(listed.get(idx));
                        count += 1;
                    }
                }
                final CompletableFuture<String> res;
                if (count == listed.size()) {
                    res = CompletableFuture.completedFuture(null);
                } else if (count == 0) {
                    res = target.delete(pkg.versionsKey())
                        .thenApply(deleted -> pkg.rootKey().string());
                } else {
                    res = target.save(
                        pkg.versionsKey(),
                        new Content.From(
                            Json.createObjectBuilder(json).add(VersionsPruner.VERSIONS, kept)
                                .build().toString().getBytes(StandardCharsets.UTF_8)
                        )
                    ).thenApply(saved -> pkg.rootKey().string());
                }
                return res;
            }
        );
    }
}
