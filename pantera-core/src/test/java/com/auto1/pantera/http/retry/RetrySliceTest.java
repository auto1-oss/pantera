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
package com.auto1.pantera.http.retry;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Tests for {@link RetrySlice}.
 */
class RetrySliceTest {

    @Test
    void returnsSuccessWithoutRetry() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(200));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void retriesOn500AndSucceeds() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            if (calls.incrementAndGet() <= 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.internalError().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(200));
        assertThat(calls.get(), equalTo(2));
    }

    @Test
    void doesNotRetryOn404() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(404));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void respectsMaxRetries() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError().build()
            );
        };
        final Response response = new RetrySlice(origin, 3, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(500));
        // 1 initial + 3 retries = 4 total calls
        assertThat(calls.get(), equalTo(4));
    }

    @Test
    void retriesOnException() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            if (calls.incrementAndGet() <= 1) {
                final CompletableFuture<Response> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("connection reset"));
                return future;
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(200));
        assertThat(calls.get(), equalTo(2));
    }

    @Test
    void zeroRetriesMeansNoRetry() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError().build()
            );
        };
        final Response response = new RetrySlice(origin, 0, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(500));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void doesNotRetryOn400() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(400));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void usesDefaultConfiguration() throws Exception {
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final Response response = new RetrySlice(origin)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(200));
    }

    @Test
    void retriesOn503() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Slice origin = (line, headers, body) -> {
            if (calls.incrementAndGet() <= 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unavailable().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            );
        };
        final Response response = new RetrySlice(origin, 2, Duration.ofMillis(1), 1.0)
            .response(
                new RequestLine(RqMethod.GET, "/test"),
                Headers.EMPTY, Content.EMPTY
            ).get();
        assertThat(response.status().code(), equalTo(200));
        assertThat(calls.get(), equalTo(2));
    }

    @Test
    void retriesWithJitter() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        final Slice failing = (line, headers, body) -> {
            timestamps.add(System.nanoTime());
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.internalError().build()
            );
        };
        final RetrySlice retry = new RetrySlice(failing, 2, Duration.ofMillis(100), 2.0);
        retry.response(
            new RequestLine("GET", "/test"),
            Headers.EMPTY,
            Content.EMPTY
        ).handle((resp, err) -> null).join();
        assertThat(calls.get(), equalTo(3));
        if (timestamps.size() >= 3) {
            final long firstRetryDelay =
                (timestamps.get(1) - timestamps.get(0)) / 1_000_000;
            assertThat(
                "First retry delay >= 90ms",
                firstRetryDelay,
                greaterThanOrEqualTo(90L)
            );
        }
    }
}
