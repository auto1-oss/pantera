/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Circuit breaker slice delegating to {@link AutoBlockRegistry}.
 * Fails fast with 503 when the remote is auto-blocked.
 * Records success/failure to the registry after each request.
 *
 * @since 1.0
 */
public final class CircuitBreakerSlice implements Slice {

    private final Slice origin;
    private final AutoBlockRegistry registry;
    private final String remoteId;

    /**
     * Constructor.
     * @param origin Origin slice (upstream)
     * @param registry Shared auto-block registry
     * @param remoteId Unique identifier for this remote
     */
    public CircuitBreakerSlice(
        final Slice origin,
        final AutoBlockRegistry registry,
        final String remoteId
    ) {
        this.origin = origin;
        this.registry = registry;
        this.remoteId = remoteId;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.registry.isBlocked(this.remoteId)) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.serviceUnavailable(
                    "Auto-blocked - remote unavailable: " + this.remoteId
                ).build()
            );
        }
        return this.origin.response(line, headers, body)
            .handle((resp, error) -> {
                if (error != null) {
                    this.registry.recordFailure(this.remoteId);
                    throw new CompletionException(error);
                }
                if (resp.status().code() >= 500) {
                    this.registry.recordFailure(this.remoteId);
                } else {
                    this.registry.recordSuccess(this.remoteId);
                }
                return resp;
            });
    }
}
