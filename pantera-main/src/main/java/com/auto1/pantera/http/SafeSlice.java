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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Slice which handles all exceptions and respond with 500 error in that case.
 */
final class SafeSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Wraps slice with safe decorator.
     * @param origin Origin slice
     */
    SafeSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        try {
            return this.origin.response(line, headers, body);
        } catch (final Exception err) {
            EcsLogger.error("com.auto1.pantera.http")
                .message("Failed to respond to request")
                .eventCategory("web")
                .eventAction("request_handling")
                .eventOutcome("failure")
                .error(err)
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.internalError()
                .textBody("Failed to respond to request: " + err.getMessage())
                .build()
            );
        }
    }
}
