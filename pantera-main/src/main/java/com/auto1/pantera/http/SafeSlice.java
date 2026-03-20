/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Slice which handles all exceptions and respond with 500 error in that case.
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
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
                .eventCategory("http")
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
