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
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;

/**
 * NPM audit endpoint handler for hosted/local repositories.
 * 
 * Endpoint: POST /-/npm/v1/security/advisories/bulk
 * 
 * Returns 200 OK with empty JSON object {} (no vulnerabilities found).
 * This is standard behavior for registries without vulnerability databases.
 *
 * @since 1.0
 */
public final class AuditSlice implements Slice {
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // For hosted/local repositories: return empty audit response (no vulnerabilities found)
        // This is standard behavior - if no vulnerability database is configured,
        // return empty results rather than error
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.ok()
                .jsonBody(Json.createObjectBuilder().build())  // Empty JSON object {}
                .build()
        );
    }
}
