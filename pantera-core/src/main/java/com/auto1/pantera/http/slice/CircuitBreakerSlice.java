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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
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
            // Observability bug-fix: previously this fail-fast 503 was
            // emitted silently — no log line, no event.action, no trace.id
            // correlation. Operators investigating "why did client X get
            // 503?" had no way to tell the circuit breaker from a real
            // upstream 5xx or an overload. WARN level because a tripped
            // circuit means upstream has already failed N times; it's a
            // noteworthy event but not an error originating in Pantera.
            EcsLogger.warn("com.auto1.pantera.http.client")
                .message("Circuit breaker OPEN — fast-failing with 503 without upstream call"
                    + " (remote=" + this.remoteId + ")")
                .eventCategory("web")
                .eventAction("circuit_breaker_open")
                .eventOutcome("failure")
                .field("event.reason", "auto_block_active")
                .field("url.path", line.uri().getPath())
                .field("http.response.status_code", 503)
                .log();
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
