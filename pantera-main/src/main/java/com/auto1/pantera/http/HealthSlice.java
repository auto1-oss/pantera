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
