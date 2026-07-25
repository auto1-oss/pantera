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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * {@code GET /-/ping} — npm's registry liveness contract. Real npm registries
 * answer with a bare {@code {}} 200; {@code npm ping} treats any non-2xx (or
 * a connection failure) as a dead registry.
 *
 * @since 2.3.0
 */
public final class PingSlice implements Slice {

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.ok().jsonBody("{}").build()
        );
    }
}
