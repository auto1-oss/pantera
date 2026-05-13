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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.gem.Gem;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A slice, which servers gem packages.
 */
final class SubmitGemSlice implements Slice {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "gem";

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Gem SDK.
     */
    private final Gem gem;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String name;

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Legacy ctor (no synchronous index writer).
     *
     * @param storage The storage.
     * @param events Artifact events
     * @param name Repository name
     */
    SubmitGemSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String name) {
        this(storage, events, name, com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     *
     * @param storage The storage.
     * @param events Artifact events
     * @param name Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    SubmitGemSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String name,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this.storage = storage;
        this.gem = new Gem(storage);
        this.events = events;
        this.name = name;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers,
                                                final Content body) {
        final Key key = new Key.From(
            "gems", UUID.randomUUID().toString().replace("-", "").concat(".gem")
        );
        return this.storage.save(
                key, new ContentWithSize(body, headers)
            ).thenCompose(
                none -> {
                    final CompletionStage<Pair<String, String>> update = this.gem.update(key);
                    return update.thenCompose(
                        pair -> new RqHeaders(headers, "content-length").stream().findFirst()
                            .map(Long::parseLong).map(CompletableFuture::completedFuture)
                            .orElseGet(
                                () -> this.storage.metadata(key)
                                    .thenApply(mets -> mets.read(Meta.OP_SIZE).get())
                            ).thenCompose(size -> {
                                final ArtifactEvent event = new ArtifactEvent(
                                    SubmitGemSlice.REPO_TYPE, this.name,
                                    new Login(headers).getValue(),
                                    pair.getKey(), pair.getValue(), size
                                );
                                this.events.ifPresent(queue -> queue.add(event));
                                return this.syncIndex.recordSync(event);
                            })
                    );
                }
            )
            .thenCompose(none -> this.storage.delete(key))
            .thenApply(none -> ResponseBuilder.created().build());
    }
}
