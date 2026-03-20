/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
