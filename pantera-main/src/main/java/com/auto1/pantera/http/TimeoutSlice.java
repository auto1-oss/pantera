/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Slice decorator that adds timeout to requests.
 * Prevents hanging requests by timing out after specified duration.
 * 
 * <p>Timeout is configured in artipie.yml under meta.http_client.proxy_timeout
 * (default: 120 seconds)</p>
 *
 * @since 1.0
 */
public final class TimeoutSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Timeout duration in seconds.
     */
    private final long timeoutSeconds;

    /**
     * Ctor with explicit timeout in seconds.
     *
     * @param origin Origin slice
     * @param timeoutSeconds Timeout duration in seconds
     */
    public TimeoutSlice(final Slice origin, final long timeoutSeconds) {
        this.origin = origin;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.origin.response(line, headers, body)
            .orTimeout(this.timeoutSeconds, TimeUnit.SECONDS);
    }
}
