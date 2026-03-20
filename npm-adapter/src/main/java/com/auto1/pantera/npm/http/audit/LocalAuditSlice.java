/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.audit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
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
