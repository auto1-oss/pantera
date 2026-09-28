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
package com.auto1.pantera.adapters;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadOnlyProxySlice}.
 *
 * @since 2.2.9
 */
final class ReadOnlyProxySliceTest {

    @Test
    void uploadToAProxyIsMethodNotAllowed() {
        final AtomicInteger calls = new AtomicInteger();
        final Response resp = new ReadOnlyProxySlice(counting(calls)).response(
            new RequestLine(RqMethod.PUT, "/com/qa/z/1/z-1.jar"),
            Headers.EMPTY,
            new Content.From("x".getBytes())
        ).join();
        MatcherAssert.assertThat(
            "a PUT to a proxy answers 405",
            resp.status(),
            new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "the 405 advertises the supported methods",
            resp.headers().values("Allow").get(0),
            new IsEqual<>("GET, HEAD")
        );
        MatcherAssert.assertThat(
            "the upstream is never contacted",
            calls.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void readsAreForwarded() {
        final AtomicInteger calls = new AtomicInteger();
        final Response resp = new ReadOnlyProxySlice(counting(calls)).response(
            new RequestLine(RqMethod.HEAD, "/a"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.OK));
    }

    @Test
    void explicitlyAllowedMethodsAreForwarded() {
        final AtomicInteger calls = new AtomicInteger();
        new ReadOnlyProxySlice(counting(calls), RqMethod.POST).response(
            new RequestLine(RqMethod.POST, "/-/npm/v1/security/advisories/bulk"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(1));
    }

    private static Slice counting(final AtomicInteger calls) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
    }
}
