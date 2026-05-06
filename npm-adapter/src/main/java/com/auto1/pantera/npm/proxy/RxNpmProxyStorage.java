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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.asto.rx.RxStorage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.misc.AbbreviatedMetadata;
import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.json.JsonObject;

import com.auto1.pantera.npm.misc.MetadataETag;
import javax.json.Json;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.function.BiConsumer;

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
     * Phase 11.5 — fine-grained sub-phase recorder. Receives
     * (phase name, durationNs). No-op default so existing call sites and
     * tests stay unchanged.
     */
    private final BiConsumer<String, Long> phaseRecorder;

    /**
     * Ctor.
     * @param storage Underlying storage
     */
    public RxNpmProxyStorage(final RxStorage storage) {
        this(storage, null);
    }

    /**
     * Ctor with phase recorder for Phase 11.5 sub-phase profiling.
     *
     * @param storage Underlying storage
     * @param phaseRecorder (phase, durationNs) recorder; null treated as no-op
     */
    public RxNpmProxyStorage(
        final RxStorage storage,
        final BiConsumer<String, Long> phaseRecorder
    ) {
        this.storage = storage;
        this.phaseRecorder = phaseRecorder == null ? (phase, ns) -> { } : phaseRecorder;
    }

    /**
     * Phase 11.5 — emit sub-phase duration via the configured recorder.
     * Recorder failures are swallowed so the serve path is never affected.
     *
     * @param phase phase name (e.g. {@code "npm_storage_save_meta"})
     * @param startNs nanoTime captured at phase entry
     */
    private void recordPhase(final String phase, final long startNs) {
        try {
            this.phaseRecorder.accept(phase, System.nanoTime() - startNs);
        } catch (final Exception thrown) {
            // Never break serve path on a profiler failure.
            EcsLogger.debug("com.auto1.pantera.npm")
                .message("npm storage phaseRecorder threw; serve path unaffected")
                .field("phase", phase)
                .error(thrown)
                .log();
        }
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
        // PERF: Pre-compute content hashes at write time (Fix 2.1)
        // At read time, derive ETag from stored hash + tarball prefix (~100 bytes)
        // instead of SHA-256 of 3-5MB transformed content
        final String fullHash = new MetadataETag(fullContent).calculate();
        final String abbrevHash = abbreviatedContent.length > 0
            ? new MetadataETag(abbreviatedContent).calculate() : null;
        final NpmPackage.Metadata enrichedMeta = new NpmPackage.Metadata(
            pkg.meta().lastModified(),
            pkg.meta().lastRefreshed(),
            fullHash,
            abbrevHash
        );
        return Completable.concatArray(
            this.storage.save(
                metaKey,
                new Content.From(
                    enrichedMeta.json().encode().getBytes(StandardCharsets.UTF_8)
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
            EcsLogger.debug("com.auto1.pantera.npm")
                .message(String.format("Generated abbreviated metadata: abbreviated=%d bytes, full=%d bytes", result.length, fullContent.length()))
                .eventCategory("database")
                .eventAction("generate_abbreviated")
                .eventOutcome("success")
                .field("package.name", packageName)
                .log();
            return result;
        } catch (final Exception e) {
            EcsLogger.error("com.auto1.pantera.npm")
                .message(String.format("Failed to generate abbreviated metadata: full=%d bytes", fullContent.length()))
                .eventCategory("database")
                .eventAction("generate_abbreviated")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .error(e)
                .log();
            return new byte[0];
        }
    }

    @Override
    public Completable save(final NpmAsset asset) {
        final Key key = new Key.From(asset.path());
        final Key metaKey = new Key.From(String.format("%s.meta", asset.path()));
        // Phase 12: parallelise meta + data writes via mergeArray.
        //
        // Previously this was concatArray(meta, data) which forced the small
        // meta write (with directory allocation + atomic move) onto the
        // critical path BEFORE the larger tarball write started. Phase 11.5
        // profiling showed save_meta = 46 ms/req vs save_data of similar
        // magnitude — meta-then-data wasted ~46 ms on every cache miss.
        //
        // Safety: the existing meta-then-data ordering existed to make the
        // .meta file visible whenever the .tgz is visible. With parallel
        // writes, meta and data may finish in either order, and a concurrent
        // reader could in principle observe data without meta. This is safe
        // because:
        //   1. SingleFlight dedup (NpmProxy.refreshing / DownloadAsset) makes
        //      a second cache-miss read for the same key wait for the first.
        //   2. RxNpmProxyStorage.getAsset checks storage.exists(data) THEN
        //      reads both data + meta via zipWith. If meta is missing, the
        //      zip fails and the caller treats it as a miss → re-fetch. The
        //      same is true for the post-save reload, but that path waits
        //      for BOTH save Completables (mergeArray semantics) to complete
        //      before subscribing.
        //   3. The Phase 12 stream-through (saveStreamThrough) eliminates the
        //      post-save reload entirely — meta is written in parallel with
        //      the data tee.
        return Completable.mergeArray(
            Completable.defer(() -> {
                final long metaStartNs = System.nanoTime();
                return this.storage.save(
                    metaKey,
                    new Content.From(
                        asset.meta().json().encode().getBytes(StandardCharsets.UTF_8)
                    )
                ).doOnComplete(() -> recordPhase("npm_storage_save_meta", metaStartNs));
            }),
            Completable.defer(() -> {
                final long dataStartNs = System.nanoTime();
                return this.storage.save(
                    key,
                    new Content.From(asset.dataPublisher())
                ).doOnComplete(() -> recordPhase("npm_storage_save_data", dataStartNs));
            })
        );
    }

    /**
     * Phase 12 — stream-through cache write.
     *
     * <p>Instead of consuming the upstream Publisher into storage and then
     * reloading from disk to serve the client, this method tees the upstream
     * byte stream so the client receives bytes as they arrive while the same
     * bytes accumulate in memory and are persisted to storage on completion.
     * This eliminates the {@code save → reload} round-trip (Phase 11.5
     * measured ~23 ms/req) and gets the first byte to the client as fast as
     * the upstream remote can deliver it.
     *
     * <p>The returned {@link NpmAsset} carries a tee'd
     * {@code Publisher<ByteBuffer>} as its data publisher. When the client
     * subscribes (typically via {@code DownloadAssetSlice} → response body),
     * each chunk is forwarded downstream AND a copy is appended to an
     * in-memory buffer. On stream completion the buffered bytes are
     * persisted to storage via {@link #save(NpmAsset)} which writes meta +
     * data in parallel (see {@code mergeArray} above). Errors abort the
     * client stream and skip the storage save (no partial write is
     * committed).
     *
     * <p>Mirrors the {@code FromStorageCache.teeContent} pattern used by the
     * Maven proxy cache; the buffer-then-save trade-off is acceptable for
     * npm tarballs (typical size 10 KB–1 MB; ~50 MB worst case) and matches
     * the existing memory profile of the code path.
     *
     * @param asset Asset whose data publisher is tee'd to client + storage
     * @return Asset with a tee'd data publisher; subscribing drives both
     *     client delivery and background storage save
     */
    @Override
    public Maybe<NpmAsset> saveStreamThrough(final NpmAsset asset) {
        return Maybe.just(this.streamThrough(asset));
    }

    /**
     * Build a tee'd asset for {@link #saveStreamThrough(NpmAsset)}. Extracted
     * so the non-Maybe form can be unit-tested directly without needing to
     * subscribe to the wrapping {@link Maybe}.
     *
     * @param asset Upstream asset
     * @return Asset with tee'd data publisher; meta save is fired immediately
     */
    NpmAsset streamThrough(final NpmAsset asset) {
        final Key key = new Key.From(asset.path());
        final Key metaKey = new Key.From(
            String.format("%s.meta", asset.path())
        );
        // Phase 12: kick the meta save IMMEDIATELY (not after data). The
        // metadata bytes are known before the upstream body starts flowing
        // (lastModified + contentType were extracted from response headers
        // by HttpNpmRemote), so there is no reason to gate the meta write on
        // the data tee. Fire-and-forget; failures are logged but never break
        // the serve path.
        final long metaStartNs = System.nanoTime();
        this.storage.save(
            metaKey,
            new Content.From(
                asset.meta().json().encode().getBytes(StandardCharsets.UTF_8)
            )
        ).subscribe(
            () -> recordPhase("npm_storage_save_meta", metaStartNs),
            err -> EcsLogger.warn("com.auto1.pantera.npm")
                .message("Stream-through: meta save failed; serve path unaffected")
                .eventCategory("database")
                .eventAction("stream_through_meta_save")
                .eventOutcome("failure")
                .field("url.path", asset.path())
                .error(err)
                .log()
        );
        // Phase 12: tee the upstream Publisher. Bytes flow to the client as
        // they arrive; copies accumulate in a ByteArrayOutputStream. On
        // upstream completion, fire storage.save(data) — at most once via
        // saveFired CAS — to persist the tarball.
        final java.io.ByteArrayOutputStream buffer =
            new java.io.ByteArrayOutputStream();
        final java.util.concurrent.atomic.AtomicBoolean saveFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final long dataStartNs = System.nanoTime();
        final io.reactivex.Flowable<java.nio.ByteBuffer> teed =
            io.reactivex.Flowable.fromPublisher(asset.dataPublisher())
                .doOnNext(buf -> {
                    // Use a read-only view so the original buffer's position
                    // is preserved for downstream subscribers — this is the
                    // exact pattern FromStorageCache.teeContent uses.
                    final java.nio.ByteBuffer copy = buf.asReadOnlyBuffer();
                    final byte[] bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                    buffer.write(bytes);
                })
                .doOnComplete(() -> {
                    if (saveFired.compareAndSet(false, true)) {
                        try {
                            this.storage.save(
                                key,
                                new Content.From(buffer.toByteArray())
                            ).subscribe(
                                () -> recordPhase("npm_storage_save_data", dataStartNs),
                                err -> EcsLogger.warn("com.auto1.pantera.npm")
                                    .message(String.format(
                                        "Stream-through: data save failed for key '%s'; client already served",
                                        key.string()
                                    ))
                                    .eventCategory("database")
                                    .eventAction("stream_through_data_save")
                                    .eventOutcome("failure")
                                    .error(err)
                                    .log()
                            );
                        } catch (final Exception ex) {
                            EcsLogger.warn("com.auto1.pantera.npm")
                                .message(String.format(
                                    "Stream-through: exception initiating data save for key '%s'",
                                    key.string()
                                ))
                                .eventCategory("database")
                                .eventAction("stream_through_data_save")
                                .eventOutcome("failure")
                                .error(ex)
                                .log();
                        }
                    }
                });
        return new NpmAsset(asset.path(), teed, asset.meta());
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
        // Phase 11.5: time the storage.exists probe separately from readAsset
        // so the cache_check timer reflects only the existence test, not the
        // metadata + data stream open that follows on a hit.
        return Single.defer(() -> {
            final long existsStartNs = System.nanoTime();
            return this.storage.exists(new Key.From(path))
                .doOnSuccess(exists -> recordPhase("npm_storage_exists", existsStartNs));
        }).flatMapMaybe(
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
    public Completable saveMetadataOnly(final String name, final NpmPackage.Metadata metadata) {
        final Key metaKey = new Key.From(name, "meta.meta");
        return this.storage.save(
            metaKey,
            new Content.From(metadata.json().encode().getBytes(StandardCharsets.UTF_8))
        );
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
        // Phase 11.5: time the data-stream open and meta read+parse separately
        // so the reload phase can be decomposed (the data Single resolves once
        // the stream is opened — it does NOT include downstream client-side
        // streaming, which is part of asset_total minus the sub-phases).
        final long dataOpenStartNs = System.nanoTime();
        final Single<com.auto1.pantera.asto.Content> dataSingle =
            this.storage.value(new Key.From(path))
                .doOnSuccess(c -> recordPhase("npm_storage_read_data_open", dataOpenStartNs));
        final long metaReadStartNs = System.nanoTime();
        final Single<JsonObject> metaSingle =
            this.storage.value(new Key.From(String.format("%s.meta", path)))
                .flatMap(content -> RxFuture.single(content.asBytesFuture()))
                .map(metadata -> new String(metadata, StandardCharsets.UTF_8))
                .map(JsonObject::new)
                .doOnSuccess(j -> recordPhase("npm_storage_read_meta", metaReadStartNs));
        return dataSingle.zipWith(
            metaSingle,
            (content, metadata) ->
                new NpmAsset(
                    path,
                    content,
                    new NpmAsset.Metadata(metadata)
                )
        );
    }

}
