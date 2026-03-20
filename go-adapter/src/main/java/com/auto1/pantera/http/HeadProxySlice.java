/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;

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
