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
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.nuget.metadata.Nuspec;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    @Override
    public CompletionStage<PackageInfo> add(final Content content) {
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
                        return this.storage.list(id.rootKey()).thenCompose(
                            existing -> {
                                if (!existing.isEmpty()) {
                                    throw new PackageVersionAlreadyExistsException(id.toString());
                                }
                                final PackageKeys pkey = new PackageKeys(nuspec.id());
                                return this.storage.exclusively(
                                    pkey.rootKey(),
                                    target -> CompletableFuture.allOf(
                                        this.storage.value(key)
                                            .thenCompose(val -> new Hash(val).save(target, id)),
                                        this.storage.save(
                                            new PackageIdentity(nuspec.id(), nuspec.version())
                                                .nuspecKey(),
                                            new Content.From(nuspec.bytes())
                                        )
                                    )
                                        .thenCompose(nothing -> target.move(key, id.nupkgKey()))
                                        .thenCompose(nothing -> this.versions(pkey))
                                        .thenApply(vers -> vers.add(nuspec.version()))
                                        .thenCompose(
                                            vers -> vers.save(
                                                target,
                                                pkey.versionsKey()
                                            )
                                        ).thenCompose(
                                            nothing -> this.storage.metadata(id.nuspecKey())
                                                .thenApply(meta -> meta.read(Meta.OP_SIZE).get())
                                        ).thenApply(
                                            size -> new PackageInfo(
                                                nuspec.id(), nuspec.version(), size
                                            )
                                        )
                                );
                            }
                        );
                    }
                )
        );
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
}
