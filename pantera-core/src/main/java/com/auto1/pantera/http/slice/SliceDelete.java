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
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.RepositoryEvents;
import org.slf4j.MDC;

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
        // Captured synchronously, on this call's thread — MDC is correctly
        // populated here by EcsLoggingSlice. storage.exists()/delete() may
        // complete their continuations on a DispatchedStorage worker thread
        // that never had MDC bound, so the values are threaded through this
        // closure rather than re-read from MDC inside the continuation.
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(headers).getValue();
        final KeyFromPath key = new KeyFromPath(line.uri().getPath());
        return this.storage.exists(key)
            .thenCompose(
                exists -> {
                    final CompletableFuture<Response> rsp;
                    if (exists) {
                        rsp = this.storage.delete(key).thenAccept(
                            nothing -> {
                                this.events.ifPresent(item -> item.addDeleteEventByKey(key));
                                final String repoType = this.events.map(RepositoryEvents::repoType).orElse(null);
                                final String repoName = this.events.map(RepositoryEvents::repoName).orElse(null);
                                final String artifactName = this.events.map(item -> item.artifactName(key))
                                    .orElseGet(key::string);
                                AuditLogger.delete(
                                    ctx, repoType, repoName, artifactName,
                                    RepositoryEvents.VERSION, owner,
                                    AuditLogger.OUTCOME_SUCCESS, null
                                );
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
