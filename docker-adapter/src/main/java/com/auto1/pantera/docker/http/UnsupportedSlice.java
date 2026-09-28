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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.error.UnsupportedError;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Answers 405 with the OCI {@code UNSUPPORTED} error for a Docker Registry
 * API operation the registry does not implement (manifest and blob
 * DELETE), instead of a bare 404 that reads as "no such image".
 *
 * @since 2.2.9
 */
final class UnsupportedSlice implements Slice {

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return body.discard().thenApply(
            ignored -> ResponseBuilder.methodNotAllowed()
                .jsonBody(new UnsupportedError().json())
                .build()
        );
    }
}
