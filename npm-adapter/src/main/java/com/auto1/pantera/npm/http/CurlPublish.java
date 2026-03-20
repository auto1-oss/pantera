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

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.npm.MetaUpdate;
import com.auto1.pantera.npm.Publish;
import com.auto1.pantera.npm.TgzArchive;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.json.JsonObject;

/**
 * The NPM publish front. It allows to publish new .tgz archive
 * using `curl PUT`.
 * @since 0.9
 */
final class CurlPublish implements Publish {
    /**
     * Endpoint request line pattern.
     */
    static final Pattern PTRN = Pattern.compile(".*\\.tgz");

    /**
     * Json field package name.
     */
    private static final String NAME = "name";

    /**
     * Json field version.
     */
    private static final String VERSION = "version";

    /**
     * The storage.
     */
    private final Storage storage;

    /**
     * Constructor.
     * @param storage The storage.
     */
    CurlPublish(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Publish.PackageInfo> publishWithInfo(
        final Key prefix, final Key artifact
    ) {
        return this.parsePackage(artifact).thenCompose(
            uploaded -> {
                final JsonObject pkg = uploaded.packageJson();
                final String name = pkg.getString(CurlPublish.NAME);
                final String vers = pkg.getString(CurlPublish.VERSION);
                final byte[] bytes = uploaded.bytes();
                return this.saveAndUpdate(uploaded, name, vers, bytes)
                    .thenApply(nothing -> new PackageInfo(name, vers, bytes.length));
            }
        );
    }

    @Override
    public CompletableFuture<Void> publish(final Key prefix, final Key artifact) {
        return this.parsePackage(artifact).thenCompose(
            uploaded -> {
                final JsonObject pkg = uploaded.packageJson();
                return this.saveAndUpdate(
                    uploaded, pkg.getString(CurlPublish.NAME),
                    pkg.getString(CurlPublish.VERSION), uploaded.bytes()
                );
            }
        );
    }

    /**
     * Save package and update metadata.
     * @param uploaded Parsed uploaded tgz
     * @param name Package name
     * @param vers Package version
     * @param bytes Package bytes
     * @return Completable action
     */
    private CompletableFuture<Void> saveAndUpdate(
        final TgzArchive uploaded, final String name, final String vers, final byte[] bytes
    ) {
        return CompletableFuture.allOf(
            this.storage.save(
                new Key.From(name, "-", String.format("%s-%s.tgz", name, vers)),
                new Content.From(bytes)
            ),
            new MetaUpdate.ByTgz(uploaded).update(new Key.From(name), this.storage)
        );
    }

    /**
     * Read package tgz.
     * @param artifact Artifact key
     * @return Artifact tgz archive
     */
    private CompletableFuture<TgzArchive> parsePackage(final Key artifact) {
        return new RxStorageWrapper(this.storage).value(artifact).map(Concatenation::new)
            .flatMap(Concatenation::single).map(Remaining::new)
            .map(Remaining::bytes).map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
            .map(bytes -> new TgzArchive(bytes, false))
            .to(SingleInterop.get()).toCompletableFuture();
    }
}
