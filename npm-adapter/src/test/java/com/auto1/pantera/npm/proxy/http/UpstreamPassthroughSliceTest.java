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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UpstreamPassthroughSlice} (WS4-npm.8): a bare read-through
 * forward with no caching/transformation, used for {@code /-/v1/search} and
 * dist-tags GETs against a proxy repository.
 */
final class UpstreamPassthroughSliceTest {

    @Test
    void forwardsRequestToRemoteExactlyOnce() {
        final AtomicInteger calls = new AtomicInteger();
        final Slice remote = (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().jsonBody("{\"latest\":\"1.0.0\"}").build()
            );
        };
        final Response response = new UpstreamPassthroughSlice(remote, "dist-tags").response(
            new RequestLine(RqMethod.GET, "/@hello/simple-npm-project/dist-tags"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the request reaches the remote exactly once (no accidental double-forward)",
            calls.get(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the remote's response body is forwarded verbatim",
            response.body().asString(),
            new IsEqual<>("{\"latest\":\"1.0.0\"}")
        );
    }

    @Test
    void stripsHopByHopAndInternalHeadersBeforeForwarding() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        final Slice remote = (line, headers, body) -> {
            seen.set(headers);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().jsonBody("{}").build());
        };
        final Headers inbound = new Headers();
        inbound.add("Authorization", "Bearer secret");
        inbound.add("pantera_login", "alice");
        inbound.add("Host", "internal-host");
        inbound.add("X-Forwarded-For", "10.0.0.1");
        new UpstreamPassthroughSlice(remote, "search").response(
            new RequestLine(RqMethod.GET, "/-/v1/search?text=lodash"),
            inbound, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Authorization is never forwarded upstream",
            seen.get().find("Authorization").isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the internal pantera_login header is never forwarded upstream",
            seen.get().find("pantera_login").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void consumesRequestBody() {
        final Slice remote = (line, headers, body) -> body.asBytesFuture().thenApply(
            bytes -> ResponseBuilder.ok()
                .textBody(new String(bytes, StandardCharsets.UTF_8))
                .build()
        );
        final Response response = new UpstreamPassthroughSlice(remote, "search").response(
            new RequestLine(RqMethod.GET, "/-/v1/search"),
            Headers.EMPTY, new Content.From("probe".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            response.body().asString(),
            new IsEqual<>("probe")
        );
    }
}
