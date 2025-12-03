/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.rx.RxStorage;
import com.artipie.npm.proxy.model.NpmAsset;
import com.artipie.npm.proxy.model.NpmPackage;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Base NPM Proxy storage implementation. It encapsulates storage format details
 * and allows to handle both primary data and metadata files within one calls.
 * It uses underlying RxStorage and works in Rx-way.
 */
public final class RxNpmProxyStorage implements NpmProxyStorage {
    /**
     * Underlying storage.
     */
    private final RxStorage storage;

    /**
     * Ctor.
     * @param storage Underlying storage
     */
    public RxNpmProxyStorage(final RxStorage storage) {
        this.storage = storage;
    }

    @Override
    public Completable save(final NpmPackage pkg) {
        final Key key = new Key.From(pkg.name(), "meta.json");
        final Key metaKey = new Key.From(pkg.name(), "meta.meta");
        // Save metadata FIRST, then data. This ensures readers always see
        // metadata when data exists (they check metadata to validate).
        // Sequential saves are safer than parallel - parallel can still leave
        // partial state visible to readers.
        return Completable.concatArray(
            this.storage.save(
                metaKey,
                new Content.From(
                    pkg.meta().json().encode().getBytes(StandardCharsets.UTF_8)
                )
            ),
            this.storage.save(
                key,
                new Content.From(pkg.content().getBytes(StandardCharsets.UTF_8))
            )
        );
    }

    @Override
    public Completable save(final NpmAsset asset) {
        final Key key = new Key.From(asset.path());
        final Key metaKey = new Key.From(String.format("%s.meta", asset.path()));
        // Save metadata FIRST, then data. This ensures that when a reader sees
        // the tgz file, the .meta file already exists. This prevents validation
        // failures where the background processor tries to read metadata that
        // doesn't exist yet.
        return Completable.concatArray(
            this.storage.save(
                metaKey,
                new Content.From(
                    asset.meta().json().encode().getBytes(StandardCharsets.UTF_8)
                )
            ),
            this.storage.save(
                key,
                new Content.From(asset.dataPublisher())
            )
        );
    }

    @Override
    public Maybe<NpmPackage> getPackage(final String name) {
        return this.storage.exists(new Key.From(name, "meta.json"))
            .flatMapMaybe(
                exists -> exists ? this.readPackage(name).toMaybe() : Maybe.empty()
            );
    }

    @Override
    public Maybe<NpmAsset> getAsset(final String path) {
        return this.storage.exists(new Key.From(path))
            .flatMapMaybe(
                exists -> exists ? this.readAsset(path).toMaybe() : Maybe.empty()
            );
    }

    @Override
    public Maybe<NpmPackage.Metadata> getPackageMetadata(final String name) {
        return this.storage.exists(new Key.From(name, "meta.meta"))
            .flatMapMaybe(exists -> {
                if (!exists) {
                    return Maybe.empty();
                }
                return this.storage.value(new Key.From(name, "meta.meta"))
                    .map(Content::asBytesFuture)
                    .flatMap(SingleInterop::fromFuture)
                    .map(metadata -> new String(metadata, StandardCharsets.UTF_8))
                    .map(JsonObject::new)
                    .map(NpmPackage.Metadata::new)
                    .toMaybe();
            });
    }

    @Override
    public Maybe<Content> getPackageContent(final String name) {
        return this.storage.exists(new Key.From(name, "meta.json"))
            .flatMapMaybe(exists -> {
                if (!exists) {
                    return Maybe.empty();
                }
                // Return Content directly - NO loading into memory!
                // Convert Single<Content> to Maybe<Content>
                return this.storage.value(new Key.From(name, "meta.json")).toMaybe();
            });
    }

    /**
     * Read NPM package from storage.
     * @param name Package name
     * @return NPM package
     */
    private Single<NpmPackage> readPackage(final String name) {
        return this.storage.value(new Key.From(name, "meta.json"))
            .map(Content::asBytesFuture)
            .flatMap(SingleInterop::fromFuture)
            .zipWith(
                this.storage.value(new Key.From(name, "meta.meta"))
                    .map(Content::asBytesFuture)
                    .flatMap(SingleInterop::fromFuture)
                    .map(metadata -> new String(metadata, StandardCharsets.UTF_8))
                    .map(JsonObject::new),
                (content, metadata) ->
                    new NpmPackage(
                        name,
                        new String(content, StandardCharsets.UTF_8),
                        new NpmPackage.Metadata(metadata)
                    )
                );
    }

    /**
     * Read NPM Asset from storage.
     * @param path Asset path
     * @return NPM asset
     */
    private Single<NpmAsset> readAsset(final String path) {
        return this.storage.value(new Key.From(path))
            .zipWith(
                this.storage.value(new Key.From(String.format("%s.meta", path)))
                    .map(Content::asBytesFuture)
                    .flatMap(SingleInterop::fromFuture)
                    .map(metadata -> new String(metadata, StandardCharsets.UTF_8))
                    .map(JsonObject::new),
                (content, metadata) ->
                    new NpmAsset(
                        path,
                        content,
                        new NpmAsset.Metadata(metadata)
                    )
            );
    }

}
