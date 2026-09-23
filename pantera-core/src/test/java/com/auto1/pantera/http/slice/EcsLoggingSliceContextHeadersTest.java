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
import com.auto1.pantera.http.rq.RequestLine;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The internal {@code X-Pantera-Ctx-*} headers feed {@code client.ip} and
 * {@code trace.id} of audit records, so {@link EcsLoggingSlice} must replace
 * any value a client sent under those names instead of appending to it.
 */
final class EcsLoggingSliceContextHeadersTest {

    @Test
    void replacesClientSuppliedContextHeaders() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new EcsLoggingSlice(
            (line, headers, body) -> {
                seen.set(headers);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            },
            "10.0.0.7"
        ).response(
            RequestLine.from("PUT /repo/a.jar HTTP/1.1"),
            new Headers()
                .add("x-pantera-ctx-client-ip", "203.0.113.66")
                .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "forged-trace"),
            Content.EMPTY
        ).join();
        MDC.clear();
        final List<String> ips = seen.get().values(EcsLoggingSlice.CTX_CLIENT_IP_HEADER);
        final List<String> traces = seen.get().values(EcsLoggingSlice.CTX_TRACE_ID_HEADER);
        MatcherAssert.assertThat(
            "only the server-derived client IP reaches downstream slices",
            ips, new IsEqual<>(List.of("10.0.0.7"))
        );
        MatcherAssert.assertThat(
            "exactly one trace id reaches downstream slices",
            traces.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the client-sent trace id is not forwarded as the request context",
            traces.get(0), new IsNot<>(new IsEqual<>("forged-trace"))
        );
    }
    @Test
    void clientCannotClaimInternalRoutingToKeepForgedContext() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        new EcsLoggingSlice(
            (line, headers, body) -> {
                seen.set(headers);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            },
            "10.0.0.7"
        ).response(
            RequestLine.from("PUT /repo/a.jar HTTP/1.1"),
            new Headers()
                .add("x-pantera-internal", "true")
                .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "203.0.113.66")
                .add("X-PANTERA-CTX-TRACE-ID", "forged-trace"),
            Content.EMPTY
        ).join();
        MDC.clear();
        final List<String> traces = seen.get().values(EcsLoggingSlice.CTX_TRACE_ID_HEADER);
        MatcherAssert.assertThat(
            "a client-sent internal-routing marker is not forwarded",
            seen.get().find(EcsLoggingSlice.INTERNAL_ROUTING_HEADER).isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the forged client IP does not reach downstream slices",
            seen.get().values(EcsLoggingSlice.CTX_CLIENT_IP_HEADER),
            new IsEqual<>(List.of("10.0.0.7"))
        );
        MatcherAssert.assertThat(
            "exactly one trace id reaches downstream slices",
            traces.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the forged trace id does not reach downstream slices",
            traces.get(0), new IsNot<>(new IsEqual<>("forged-trace"))
        );
    }
}
