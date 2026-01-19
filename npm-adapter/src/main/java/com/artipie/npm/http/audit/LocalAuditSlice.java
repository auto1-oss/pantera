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

/**
 * Local audit slice that returns empty vulnerability report.
 * For local/hosted repositories, we don't have vulnerability data,
 * so we return an empty JSON object indicating no vulnerabilities found.
 * 
 * @since 1.2
 */
public final class LocalAuditSlice implements Slice {
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Return empty JSON indicating no vulnerabilities found
        // This is the correct response for local repositories
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .jsonBody("{}")
                .build()
        );
    }
}
