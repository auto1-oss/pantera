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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.RepositoryEvents;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Slice to upload the resource to storage by key from path.
 * @see SliceDownload
 */
public final class SliceUpload implements Slice {

    private final Storage storage;

    /**
     * Path to key transformation.
     */
    private final Function<String, Key> transform;

    /**
     * Repository events.
     */
    private final Optional<RepositoryEvents> events;

    /**
     * Slice by key from storage.
     * @param storage Storage
     */
    public SliceUpload(final Storage storage) {
        this(storage, KeyFromPath::new);
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param transform Transformation
     */
    public SliceUpload(final Storage storage,
        final Function<String, Key> transform) {
        this(storage, transform, Optional.empty());
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param events Repository events
     */
    public SliceUpload(final Storage storage,
        final RepositoryEvents events) {
        this(storage, KeyFromPath::new, Optional.of(events));
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param transform Transformation
     * @param events Repository events
     */
    public SliceUpload(final Storage storage, final Function<String, Key> transform,
        final Optional<RepositoryEvents> events) {
        this.storage = storage;
        this.transform = transform;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = transform.apply(line.uri().getPath());
        CompletableFuture<Void> res = this.storage.save(key, new ContentWithSize(body, headers));
        if (this.events.isPresent()) {
            res = res.thenCompose(
                nothing -> this.storage.metadata(key)
                    .thenApply(meta -> meta.read(Meta.OP_SIZE).orElseThrow())
                    .thenAccept(
                        size -> this.events.get()
                            .addUploadEventByKey(key, size, headers)
                    )
            );
        }
        return res.thenCompose(
            nothing -> this.storage.metadata(key)
                .thenApply(meta -> {
                    final long size = meta.read(Meta.OP_SIZE).map(Long::longValue).orElse(0L);
                    final java.util.List<String> parts = key.parts();
                    final String filename = parts.isEmpty() ? key.string() : parts.get(parts.size() - 1);
                    AuditLogger.upload(filename, size);
                    return ResponseBuilder.created().build();
                })
        );
    }
}
