/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Lightweight health check slice for NLB/load-balancer probes.
 * Returns 200 OK immediately with no I/O, no probes, no blocking.
 * Returns 200 OK with JSON body {@code {"status":"ok"}}.
 *
 * @since 1.20.13
 */
public final class HealthSlice implements Slice {

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .jsonBody("{\"status\":\"ok\"}")
                .build()
        );
    }
}
