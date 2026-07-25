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
package com.auto1.pantera.npm.http.audit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * Local audit slice for hosted/local npm repositories.
 *
 * <p>Pantera does not maintain a vulnerability advisory database, so a local
 * repo genuinely has nothing to report — but the two endpoints npm's clients
 * call have different, incompatible empty-response shapes:</p>
 *
 * <ul>
 *   <li>{@code POST /-/npm/v1/security/advisories/bulk} — a map of package
 *       name to advisories; {@code {}} means "no known advisories for any
 *       requested package", which is valid and honest.</li>
 *   <li>{@code POST /-/npm/v1/security/audits}(/quick) — a structured quick-audit
 *       report; npm expects {@code metadata.vulnerabilities} with numeric
 *       severity counts, not a bare {@code {}}. Returning {@code {}} here
 *       previously worked only because npm degrades gracefully on a missing
 *       field, not because the shape was correct.</li>
 * </ul>
 *
 * <p>Previously this slice never consumed the request body (a Vert.x buffer
 * leak on every {@code npm audit} invocation) and returned {@code {}} for
 * both endpoints regardless of shape.</p>
 *
 * @since 1.2
 */
public final class LocalAuditSlice implements Slice {

    /**
     * Empty bulk-advisory response: no known advisories for any package.
     */
    private static final String BULK_EMPTY = "{}";

    /**
     * Empty quick-audit report: zero vulnerabilities across all severities.
     */
    private static final String QUICK_AUDIT_EMPTY = String.join(
        "",
        "{",
        "\"actions\":[],",
        "\"muted\":[],",
        "\"advisories\":{},",
        "\"attention\":[],",
        "\"metadata\":{",
        "\"vulnerabilities\":{",
        "\"info\":0,\"low\":0,\"moderate\":0,\"high\":0,\"critical\":0,\"total\":0",
        "},",
        "\"dependencies\":0,",
        "\"devDependencies\":0,",
        "\"optionalDependencies\":0,",
        "\"totalDependencies\":0",
        "}",
        "}"
    );

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Consume the request body first — always, on every path, so a
        // large dependency-tree payload from `npm audit` never leaks a
        // Vert.x buffer regardless of which shape we answer with.
        return body.asBytesFuture().thenApply(ignored -> {
            final String path = line.uri().getPath();
            final boolean bulk = path.endsWith("/bulk");
            final String json = bulk ? BULK_EMPTY : QUICK_AUDIT_EMPTY;
            EcsLogger.debug("com.auto1.pantera.npm")
                .message("Local npm audit answered with an honest empty report (no advisory DB)")
                .eventCategory("web")
                .eventAction("local_audit")
                .eventOutcome("success")
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.ok()
                .jsonBody(json)
                .build();
        });
    }
}
