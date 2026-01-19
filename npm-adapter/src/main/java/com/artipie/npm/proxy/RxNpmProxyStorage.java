/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.rx.RxFuture;
import com.artipie.asto.rx.RxStorage;
import com.artipie.http.log.EcsLogger;
import com.artipie.npm.misc.AbbreviatedMetadata;
import com.artipie.npm.proxy.model.NpmAsset;
import com.artipie.npm.proxy.model.NpmPackage;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.json.JsonObject;

import javax.json.Json;
import java.io.StringReader;
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
        final Key abbreviatedKey = new Key.From(pkg.name(), "meta.abbreviated.json");
        // Save metadata FIRST, then data. This ensures readers always see
        // metadata when data exists (they check metadata to validate).
        // Sequential saves are safer than parallel - parallel can still leave
        // partial state visible to readers.
        //
        // MEMORY OPTIMIZATION: Pre-compute and save abbreviated metadata
        // This allows serving abbreviated requests without loading full metadata
        final byte[] fullContent = pkg.content().getBytes(StandardCharsets.UTF_8);
        final byte[] abbreviatedContent = this.generateAbbreviated(pkg.name(), pkg.content());
        return Completable.concatArray(
            this.storage.save(
                metaKey,
                new Content.From(
                    pkg.meta().json().encode().getBytes(StandardCharsets.UTF_8)
                )
            ),
            this.storage.save(
                key,
                new Content.From(fullContent)
            ),
            this.storage.save(
                abbreviatedKey,
                new Content.From(abbreviatedContent)
            )
        );
    }

    /**
     * Generate abbreviated metadata from full content.
     * Includes time field for pnpm compatibility.
     * @param packageName Package name for logging context
     * @param fullContent Full package JSON content
     * @return Abbreviated JSON bytes
     */
    private byte[] generateAbbreviated(final String packageName, final String fullContent) {
        try {
            final javax.json.JsonObject fullJson = Json.createReader(
                new StringReader(fullContent)
            ).readObject();
            final javax.json.JsonObject abbreviated = new AbbreviatedMetadata(fullJson).generate();
            final byte[] result = abbreviated.toString().getBytes(StandardCharsets.UTF_8);
            // Note: Release dates are included in abbreviated metadata via the "time" field
            // (added for pnpm compatibility). No separate cache needed - cooldown filtering
            // parses dates directly from abbreviated metadata.
            EcsLogger.debug("com.artipie.npm")
                .message("Generated abbreviated metadata")
                .eventCategory("cache")
                .eventAction("generate_abbreviated")
                .eventOutcome("success")
                .field("package.name", packageName)
                .field("abbreviated.size", result.length)
                .field("full.size", fullContent.length())
                .log();
            return result;
        } catch (final Exception e) {
            EcsLogger.error("com.artipie.npm")
                .message("Failed to generate abbreviated metadata")
                .eventCategory("cache")
                .eventAction("generate_abbreviated")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("full.size", fullContent.length())
                .error(e)
                .log();
            return new byte[0];
        }
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
                    .flatMap(content -> RxFuture.single(content.asBytesFuture()))
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

    @Override
    public Maybe<Content> getAbbreviatedContent(final String name) {
        final Key abbreviatedKey = new Key.From(name, "meta.abbreviated.json");
        return this.storage.exists(abbreviatedKey)
            .flatMapMaybe(exists -> {
                if (!exists) {
                    return Maybe.empty();
                }
                // Return abbreviated Content directly - NO loading into memory!
                // This is the memory-efficient path for npm install requests
                return this.storage.value(abbreviatedKey).toMaybe();
            });
    }

    @Override
    public Maybe<Boolean> hasAbbreviatedContent(final String name) {
        final Key abbreviatedKey = new Key.From(name, "meta.abbreviated.json");
        return this.storage.exists(abbreviatedKey).toMaybe();
    }

    /**
     * Read NPM package from storage.
     * @param name Package name
     * @return NPM package
     */
    private Single<NpmPackage> readPackage(final String name) {
        return this.storage.value(new Key.From(name, "meta.json"))
            .flatMap(content -> RxFuture.single(content.asBytesFuture()))
            .zipWith(
                this.storage.value(new Key.From(name, "meta.meta"))
                    .flatMap(content -> RxFuture.single(content.asBytesFuture()))
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
                    .flatMap(content -> RxFuture.single(content.asBytesFuture()))
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
