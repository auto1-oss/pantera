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
import com.auto1.pantera.http.rq.RqMethod;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Repository HEAD request helper.
 *
 * @since 1.0
 */
final class RepoHead {

    /**
     * Remote slice.
     */
    private final Slice remote;

    /**
     * Ctor.
     *
     * @param remote Remote slice
     */
    RepoHead(final Slice remote) {
        this.remote = remote;
    }

    /**
     * Perform HEAD request.
     *
     * @param path Path
     * @return Headers if successful
     */
    CompletionStage<Optional<Headers>> head(final String path) {
        return this.remote.response(
            new RequestLine(RqMethod.HEAD, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(
            resp -> {
                // CRITICAL: Consume body to prevent Vert.x request leak
                return resp.body().asBytesFuture().thenApply(ignored -> {
                    if (resp.status().success()) {
                        return Optional.of(resp.headers());
                    }
                    return Optional.empty();
                });
            }
        );
    }
}
