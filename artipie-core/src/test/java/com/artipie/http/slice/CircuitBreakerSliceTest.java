/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.timeout.AutoBlockRegistry;
import com.artipie.http.timeout.AutoBlockSettings;
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
