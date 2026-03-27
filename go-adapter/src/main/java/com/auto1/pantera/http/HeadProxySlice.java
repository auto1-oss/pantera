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
 * HEAD proxy slice for Go.
 *
 * @since 1.0
 */
final class HeadProxySlice implements Slice {

    /**
     * Remote slice.
     */
    private final Slice remote;

    /**
     * Ctor.
     *
     * @param remote Remote slice
     */
    HeadProxySlice(final Slice remote) {
        this.remote = remote;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(
                resp -> {
                    // CRITICAL: Consume body to prevent Vert.x request leak
                    return resp.body().asBytesFuture().thenApply(ignored -> {
                        if (resp.status().success()) {
                            return ResponseBuilder.ok()
                                .headers(resp.headers())
                                .build();
                        }
                        return ResponseBuilder.notFound().build();
                    });
                }
            );
    }
}
