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
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The request's logging context is bound to the serving thread only while
 * the slice chain runs on it. An event loop that kept it would stamp the
 * {@code trace.id} of a request still in flight on every record it writes
 * for other connections in the meantime.
 *
 * @since 2.2.14
 */
final class EcsLoggingSliceThreadContextTest {

    private static final String TRACE = "trace.id";

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearAll();
    }

    @Test
    void servingThreadHasTheTraceOnlyWhileTheSliceRuns() {
        final AtomicReference<String> during = new AtomicReference<>();
        final CompletableFuture<Response> pending = new CompletableFuture<>();
        final Slice origin = (line, headers, body) -> {
            during.set(ThreadContext.get(TRACE));
            return pending;
        };
        final CompletableFuture<Response> answer = new EcsLoggingSlice(origin).response(
            new RequestLine("GET", "/repo/a.jar"), Headers.EMPTY, Content.EMPTY
        );
        final String after = ThreadContext.get(TRACE);
        pending.complete(ResponseBuilder.ok().build());
        answer.join();
        MatcherAssert.assertThat(
            "the slice chain runs under the request's trace id",
            during.get(), new IsNot<>(new IsEqual<>(null))
        );
        MatcherAssert.assertThat(
            "the thread does not keep it while the request is still in flight",
            after, new IsEqual<>(null)
        );
    }

    @Test
    void servingThreadGetsItsOwnContextBack() {
        ThreadContext.put(TRACE, "context-of-the-thread");
        new EcsLoggingSlice(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.ok().build())
        ).response(
            new RequestLine("GET", "/repo/a.jar"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            ThreadContext.get(TRACE), new IsEqual<>("context-of-the-thread")
        );
    }
}
