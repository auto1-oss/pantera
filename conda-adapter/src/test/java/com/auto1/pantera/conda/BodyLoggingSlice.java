/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.jcabi.log.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Slice decorator to log request body.
 */
final class BodyLoggingSlice implements Slice {

    private final Slice origin;

    /**
     * @param origin Origin slice
     */
    BodyLoggingSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers,
                                                Content body) {
        return new Content.From(body).asBytesFuture()
            .thenCompose(
                bytes -> {
                    Logger.debug(this.origin, new String(bytes, StandardCharsets.UTF_8));
                    return this.origin.response(line, headers, new Content.From(bytes));
                }
        );
    }
}
