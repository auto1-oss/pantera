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

/**
 * Delete decorator for Slice.
 */
public final class SliceDelete implements Slice {

    private final Storage storage;

    private final Optional<RepositoryEvents> events;

    /**
     * @param storage Storage.
     */
    public SliceDelete(final Storage storage) {
        this(storage, Optional.empty());
    }

    /**
     * @param storage Storage.
     * @param events Repository events
     */
    public SliceDelete(final Storage storage, final RepositoryEvents events) {
        this(storage, Optional.of(events));
    }

    /**
     * @param storage Storage.
     * @param events Repository events
     */
    public SliceDelete(final Storage storage, final Optional<RepositoryEvents> events) {
        this.storage = storage;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final KeyFromPath key = new KeyFromPath(line.uri().getPath());
        return this.storage.exists(key)
            .thenCompose(
                exists -> {
                    final CompletableFuture<Response> rsp;
                    if (exists) {
                        rsp = this.storage.delete(key).thenAccept(
                            nothing -> {
                                this.events.ifPresent(item -> item.addDeleteEventByKey(key));
                                final java.util.List<String> parts = key.parts();
                                final String filename = parts.isEmpty() ? key.string() : parts.get(parts.size() - 1);
                                AuditLogger.delete(filename);
                            }
                        ).thenApply(none -> ResponseBuilder.noContent().build());
                    } else {
                        // Consume request body to prevent Vert.x request leak
                        rsp = body.asBytesFuture().thenApply(ignored ->
                            ResponseBuilder.notFound().build()
                        );
                    }
                    return rsp;
                }
        );
    }
}
