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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.headers.ReasonPhrase;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.pypi.meta.ValidFilename;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.asto.rx.RxFuture;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * WheelSlice save and manage whl and tgz entries.
 */
final class WheelSlice implements Slice {

    private static final String TYPE = "pypi";

    private final Storage storage;

    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Legacy ctor (no synchronous index writer).
     *
     * @param storage Storage.
     * @param events Events queue
     * @param rname Repository name
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this(storage, events, rname,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     *
     * @param storage Storage.
     * @param events Events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers iterable,
        final Content publisher
    ) {
        final Key.From key = new Key.From(UUID.randomUUID().toString());
        return this.filePart(iterable, publisher, key).thenCompose(
            filename -> this.storage.value(key).thenCompose(
                val -> new ContentAsStream<PackageInfo>(val).process(
                    input -> new Metadata.FromArchive(input, filename).read()
                )
            ).thenCompose(
                info -> {
                    final CompletionStage<Response> res;
                    if (new ValidFilename(info, filename).valid()) {
                        res = this.publish(key, filename, info, iterable);
                    } else {
                        res = this.storage.delete(key).thenApply(
                            nothing -> ResponseBuilder.badRequest()
                                .textBody(
                                    String.format(
                                        "Filename '%s' does not match the package metadata"
                                            + " (name '%s', version '%s')",
                                        filename, info.name(), info.version()
                                    )
                                )
                                .build()
                        );
                    }
                    return res;
                }
            )
        ).handle(
            (response, throwable) -> {
                if(throwable != null){
                    return ResponseBuilder.badRequest(throwable).build();
                }
                return response;
            }
        ).toCompletableFuture();
    }

    /**
     * Publish a validated upload.
     *
     * <p>The target key is always {@code <normalized-name>/<version>/<file>}
     * relative to the repository root, whatever sub-path the client posted
     * to (twine's conventional {@code /legacy/} included): the storage key,
     * the sidecar and both indexes must agree on one layout, or the package
     * index is rebuilt from the wrong prefix and hides every earlier
     * release.</p>
     *
     * <p>A released file is immutable (PyPI file-name reuse policy): an
     * identical re-upload is an idempotent 200, a different file under an
     * existing name is refused with 400 "File already exists" so pinned
     * hashes keep verifying.</p>
     *
     * @param temp Temporary key holding the upload
     * @param filename Uploaded filename
     * @param info Package metadata read from the archive
     * @param headers Request headers
     * @return Response
     */
    private CompletionStage<Response> publish(
        final Key temp, final String filename, final PackageInfo info, final Headers headers
    ) {
        final String packageName = new NormalizedProjectName.Simple(info.name()).value();
        final Key name = new Key.From(packageName, info.version(), filename);
        return this.storage.exists(name).thenCompose(
            exists -> {
                final CompletionStage<Response> res;
                if (exists) {
                    res = this.existing(temp, name, filename);
                } else {
                    res = this.store(temp, name, packageName, info, headers)
                        .thenApply(ignored -> ResponseBuilder.from(RsStatus.CREATED).build());
                }
                return res;
            }
        );
    }

    /**
     * Answer an upload whose target file already exists.
     *
     * @param temp Temporary key holding the upload
     * @param name Existing file key
     * @param filename Uploaded filename
     * @return 200 when the bytes are identical, 400 otherwise
     */
    private CompletionStage<Response> existing(
        final Key temp, final Key name, final String filename
    ) {
        return this.sha256(temp).thenCombine(this.sha256(name), String::equals)
            .thenCompose(
                same -> this.storage.delete(temp).thenApply(
                    nothing -> {
                        final Response response;
                        if (same) {
                            response = ResponseBuilder.ok().build();
                        } else {
                            EcsLogger.warn("com.auto1.pantera.pypi")
                                .message(
                                    "Refused re-upload of an existing file with different content"
                                )
                                .eventCategory("web")
                                .eventAction("artifact_upload")
                                .eventOutcome("failure")
                                .field("event.reason", "file_exists")
                                .field("repository.name", this.rname)
                                .field("file.name", filename)
                                .field("log.source", "application")
                                .log();
                            response = ResponseBuilder.badRequest()
                                // twine prints only the status line: say why there,
                                // as PyPI does.
                                .header(new ReasonPhrase("File already exists"))
                                .textBody(
                                    String.format(
                                        "File already exists: '%s'. A published file cannot be"
                                            + " replaced; publish a new version instead.",
                                        filename
                                    )
                                )
                                .build();
                        }
                        return response;
                    }
                )
            );
    }

    /**
     * SHA-256 of a stored value.
     * @param key Key
     * @return Hex digest
     */
    private CompletionStage<String> sha256(final Key key) {
        return this.storage.value(key).thenCompose(
            value -> new ContentDigest(value, Digests.SHA256).hex()
        );
    }

    /**
     * Move the upload into place, record it, write its sidecar and rebuild
     * the package and repository indexes.
     *
     * @param temp Temporary key holding the upload
     * @param name Target key
     * @param packageName Normalized package name
     * @param info Package metadata
     * @param headers Request headers
     * @return Completion
     */
    private CompletionStage<Void> store(
        final Key temp, final Key name, final String packageName,
        final PackageInfo info, final Headers headers
    ) {
        CompletionStage<Void> move = this.storage.move(temp, name);
        if (this.events.isPresent()) {
            move = move.thenCompose(ignored -> this.putArtifactToQueue(name, info, headers));
        }
        // Create sidecar metadata for PEP 503/691 compliance
        return move.thenCompose(
            ignored -> PypiSidecar.write(
                this.storage,
                name,
                info.requiresPython(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
            )
        ).thenCompose(
            ignored -> new IndexGenerator(
                this.storage, new Key.From(packageName), "/"
            ).generate()
        ).thenCompose(
            ignored -> new IndexGenerator(this.storage, Key.ROOT, "/").generateRepoIndex()
        );
    }

    /**
     * File part from multipart body.
     * @param headers Request headers
     * @param body Request body
     * @param temp Temp key to save the part
     * @return Part with the file
     */
    private CompletionStage<String> filePart(final Headers headers,
        final Publisher<ByteBuffer> body, final Key temp) {
        return Flowable.fromPublisher(
            new RqMultipart(headers, body).inspect(
                (part, inspector) -> {
                    if ("content".equals(new ContentDisposition(part.headers()).fieldName())) {
                        inspector.accept(part);
                    } else {
                        inspector.ignore(part);
                    }
                    final CompletableFuture<Void> res = new CompletableFuture<>();
                    res.complete(null);
                    return res;
                }
            )
        ).doOnNext(
            part -> EcsLogger.debug("com.auto1.pantera.pypi")
                .message("WS: multipart request body parsed, part found: " + part.toString())
                .eventCategory("web")
                .eventAction("upload")
                .field("log.source", "application")
                .log()
        ).flatMapSingle(
            // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
            part -> RxFuture.single(
                this.storage.save(temp, new Content.From(part))
                    .thenRun(() -> EcsLogger.debug("com.auto1.pantera.pypi")
                        .message("WS: content saved to temp file")
                        .eventCategory("web")
                        .eventAction("upload")
                        .field("file.name", temp.string())
                        .log())
                    .thenApply(nothing -> new ContentDisposition(part.headers()).fileName())
            )
        ).toList().map(
            items -> {
                if (items.isEmpty()) {
                    throw new PanteraException("content part was not found");
                }
                if (items.size() > 1) {
                    throw new PanteraException("multiple content parts were found");
                }
                return items.get(0);
            }
        ).to(SingleInterop.get());
    }

    /**
     * Put uploaded artifact info into events queue.
     * @param key Artifact key in the storage
     * @param info Artifact info
     * @param headers Request headers
     * @return Completion action
     */
    private CompletionStage<Void> putArtifactToQueue(
        final Key key, final PackageInfo info,
        Headers headers
    ) {
        final String normalized = new NormalizedProjectName.Simple(info.name()).value();
        return this.storage.metadata(key).thenApply(meta -> meta.read(Meta.OP_SIZE).get())
            .thenCompose(size -> {
                final ArtifactEvent event = new ArtifactEvent(
                    WheelSlice.TYPE,
                    this.rname,
                    new Login(headers).getValue(),
                    normalized,
                    info.version(),
                    size,
                    System.currentTimeMillis(),
                    null,
                    key.string()
                ).withRequestContext(headers);
                this.events.ifPresent(queue -> queue.add(event));
                // Drop any cached 404 for this package so requests that
                // 404'd before publish do not keep returning 404.
                com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
                    .invalidateAfterUpload("pypi", normalized);
                com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry.instance()
                    .invalidateAfterUpload("pypi", normalized);
                return this.syncIndex.recordSync(event);
            });
    }
}
