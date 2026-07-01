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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.scheduling.RepositoryEvents;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class DeleteSlice implements Slice {
    private final Storage asto;

    private final Optional<RepositoryEvents> events;

    public DeleteSlice(final Storage asto) {
        this(asto, Optional.empty());
    }

    public DeleteSlice(final Storage asto, final Optional<RepositoryEvents> events) {
        this.asto = asto;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(headers).getValue();
        final Key key = new KeyFromPath(line.uri().getPath());

        return this.asto.exists(key).thenCompose(
                exists -> {
                    if (exists) {
                        return this.asto.delete(key).thenApply(
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
                                    return ResponseBuilder.ok().build();
                                }
                        ).toCompletableFuture();
                    } else {
                        // Consume request body to prevent Vert.x request leak
                        return body.asBytesFuture().thenApply(ignored -> {
                            final String repoType = this.events.map(RepositoryEvents::repoType).orElse(null);
                            final String repoName = this.events.map(RepositoryEvents::repoName).orElse(null);
                            final String artifactName = this.events.map(item -> item.artifactName(key))
                                .orElseGet(key::string);
                            AuditLogger.delete(
                                ctx, repoType, repoName, artifactName,
                                RepositoryEvents.VERSION, owner,
                                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                            );
                            return ResponseBuilder.notFound().build();
                        });
                    }
                }
        );
    }
}
