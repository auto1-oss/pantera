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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

final class CircuitBreakerSliceTest {

    @Test
    void passesRequestsWhenHealthy() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(AutoBlockSettings.defaults());
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        final var resp = slice.response(
            new RequestLine("GET", "/test"), Headers.EMPTY, Content.EMPTY
        ).join();
        assertThat(resp.status().code(), equalTo(200));
    }

    @Test
    void failsFastWhenBlocked() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        registry.recordFailure("test-remote");
        final var resp = slice.response(
            new RequestLine("GET", "/test"), Headers.EMPTY, Content.EMPTY
        ).join();
        assertThat(resp.status().code(), equalTo(503));
    }

    @Test
    void recordsFailureOnServerError() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            2, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.internalError().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        slice.response(new RequestLine("GET", "/t"), Headers.EMPTY, Content.EMPTY).join();
        slice.response(new RequestLine("GET", "/t"), Headers.EMPTY, Content.EMPTY).join();
        assertThat("Blocked after 2 failures", registry.isBlocked("test-remote"), equalTo(true));
    }

    @Test
    void recordsSuccessOnOk() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        registry.recordFailure("test-remote"); // block it
        registry.recordSuccess("test-remote");  // unblock via direct registry
        // Should pass through now
        final var resp = slice.response(
            new RequestLine("GET", "/test"), Headers.EMPTY, Content.EMPTY
        ).join();
        assertThat(resp.status().code(), equalTo(200));
    }
}
