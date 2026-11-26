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
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A Troubleshoot replay stores nothing, so it must not lead the coalesced
 * fetch of {@link CachedNpmProxySlice}: a client that joined it would be
 * sent to a cache the replay did not fill.
 *
 * @since 2.2.15
 */
final class CachedNpmProxySliceReplayTest {

    @Test
    @Timeout(120)
    void clientDoesNotJoinTheFetchOfAReplay() throws Exception {
        final CompletableFuture<Response> parked = new CompletableFuture<>();
        final CountDownLatch replaying = new CountDownLatch(1);
        final AtomicInteger asked = new AtomicInteger();
        final Slice origin = (line, headers, body) -> {
            asked.incrementAndGet();
            final CompletableFuture<Response> answer;
            if (RequestTrace.instance().traced(headers)) {
                replaying.countDown();
                answer = parked;
            } else {
                answer = CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody("client").build()
                );
            }
            return answer;
        };
        final Slice slice = new CachedNpmProxySlice(
            origin, Optional.empty(), "npm-proxy-flight", "https://registry.npmjs.org", "npm-proxy"
        );
        final RequestLine line = new RequestLine(RqMethod.GET, "/flight-replay");
        final String trace = RequestTrace.instance().open();
        try {
            final CompletableFuture<Response> replay = slice.response(
                line, Headers.from(new Header(RequestTrace.HEADER, trace)), Content.EMPTY
            );
            MatcherAssert.assertThat(
                "the replay is in flight",
                replaying.await(60, TimeUnit.SECONDS), new IsEqual<>(true)
            );
            // The replay stays parked: a client that waited for it is
            // never answered. The bound only ends that wait, it is not a
            // bound on latency.
            final Response client = slice.response(line, Headers.EMPTY, Content.EMPTY)
                .get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "a client is answered while the replay is still in flight",
                new String(
                    client.body().asBytesFuture().get(60, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8
                ),
                new IsEqual<>("client")
            );
            MatcherAssert.assertThat(
                "by a fetch of its own", asked.get(), new IsEqual<>(2)
            );
            parked.complete(ResponseBuilder.ok().textBody("replay").build());
            MatcherAssert.assertThat(
                "the replay gets its own answer",
                new String(
                    replay.get(60, TimeUnit.SECONDS).body().asBytesFuture()
                        .get(60, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8
                ),
                new IsEqual<>("replay")
            );
        } finally {
            RequestTrace.instance().close(trace);
        }
    }
}
