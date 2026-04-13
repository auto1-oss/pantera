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
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.KeyFromPath;
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

    /**
     * Ctor.
     *
     * @param storage Storage.
     * @param events Evenst queue
     * @param rname Repository name
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
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
                    final CompletionStage<RsStatus> res;
                    if (new ValidFilename(info, filename).valid()) {
                        // Organize by version: <repo>/<package_name>/<version>/<filename>
                        final String packageName = new NormalizedProjectName.Simple(info.name()).value();
                        final Key name = new Key.From(
                            new KeyFromPath(line.uri().toString()),
                            packageName,
                            info.version(),
                            filename
                        );
                        CompletionStage<Void> move = this.storage.move(key, name);
                        if (this.events.isPresent()) {
                            move = move.thenCompose(
                                ignored ->
                                    this.putArtifactToQueue(name, info, filename, iterable)
                            );
                        }
                        // Create sidecar metadata for PEP 503/691 compliance
                        move = move.thenCompose(
                            ignored -> PypiSidecar.write(
                                this.storage,
                                new Key.From(packageName, info.version(), filename),
                                info.requiresPython(),
                                Instant.now()
                            )
                        );
                        // Regenerate package-level index.html after upload
                        final Key packageKey = new Key.From(
                            new KeyFromPath(line.uri().toString()),
                            packageName
                        );
                        move = move.thenCompose(
                            ignored -> new IndexGenerator(
                                this.storage,
                                packageKey,
                                line.uri().getPath()
                            ).generate()
                        );
                        // Regenerate repository-level index.html
                        final Key repoKey = new KeyFromPath(line.uri().toString());
                        move = move.thenCompose(
                            ignored -> new IndexGenerator(
                                this.storage,
                                repoKey,
                                line.uri().getPath()
                            ).generateRepoIndex()
                        );
                        res = move.thenApply(ignored -> RsStatus.CREATED);
                    } else {
                        res = this.storage.delete(key)
                            .thenApply(nothing -> RsStatus.BAD_REQUEST);
                    }
                    return res.thenApply(s -> ResponseBuilder.from(s).build());
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
                .eventCategory("repository")
                .eventAction("upload")
                .log()
        ).flatMapSingle(
            // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
            part -> RxFuture.single(
                this.storage.save(temp, new Content.From(part))
                    .thenRun(() -> EcsLogger.debug("com.auto1.pantera.pypi")
                        .message("WS: content saved to temp file")
                        .eventCategory("repository")
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
     * @param filename Artifact filename
     * @param headers Request headers
     * @return Completion action
     */
    private CompletionStage<Void> putArtifactToQueue(
        final Key key, final PackageInfo info, final String filename,
        Headers headers
    ) {
        return this.storage.metadata(key).thenApply(meta -> meta.read(Meta.OP_SIZE).get())
            .thenAccept(
                size -> this.events.get().add(
                    new ArtifactEvent(
                        WheelSlice.TYPE,
                        this.rname,
                        new Login(headers).getValue(),
                        new NormalizedProjectName.Simple(info.name()).value(),
                        info.version(),
                        size
                    )
                )
            );
    }
}
