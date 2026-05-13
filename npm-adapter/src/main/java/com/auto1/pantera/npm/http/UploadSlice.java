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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.npm.Publish;
import com.auto1.pantera.scheduling.ArtifactEvent;
import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * UploadSlice.
 */
public final class UploadSlice implements Slice {

    /**
     * Repository type.
     */
    public static final String REPO_TYPE = "npm";

    /**
     * The npm publish front.
     */
    private final Publish npm;

    /**
     * Abstract Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Synchronous artifact-index writer for read-after-write consistency.
     */
    private final SyncArtifactIndexer syncIndex;

    /**
     * Legacy ctor (no synchronous index writer).
     *
     * @param npm Npm publish front
     * @param storage Abstract storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    public UploadSlice(final Publish npm, final Storage storage,
        final Optional<Queue<ArtifactEvent>> events, final String rname) {
        this(npm, storage, events, rname, SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     *
     * @param npm Npm publish front
     * @param storage Abstract storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    public UploadSlice(final Publish npm, final Storage storage,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final SyncArtifactIndexer syncIndex) {
        this.npm = npm;
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final String pkg = new PackageNameFromUrl(line).value();
        final Key uploaded = new Key.From(String.format("%s-%s-uploaded", pkg, UUID.randomUUID()));
        // OPTIMIZATION: Use size hint for efficient pre-allocation
        final long bodySize = body.size().orElse(-1L);
        return Concatenation.withSize(body, bodySize).single()
            .map(Remaining::new)
            .map(Remaining::bytes)
            .to(SingleInterop.get())
            .thenCompose(bytes -> this.storage.save(uploaded, new Content.From(bytes)))
            .thenCompose(
                ignored -> this.events.map(
                    queue -> this.npm.publishWithInfo(new Key.From(pkg), uploaded)
                        .thenCompose(info -> {
                            final ArtifactEvent event = new ArtifactEvent(
                                UploadSlice.REPO_TYPE, this.rname,
                                new Login(headers).getValue(),
                                info.packageName(), info.packageVersion(), info.tarSize()
                            );
                            queue.add(event);
                            return this.syncIndex.recordSync(event);
                        })
                ).orElseGet(() -> this.npm.publish(new Key.From(pkg), uploaded))
            )
            .thenCompose(ignored -> this.storage.delete(uploaded))
            .thenApply(ignored -> ResponseBuilder.ok().build())
            .toCompletableFuture();
    }
}
