/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.audit;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
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
