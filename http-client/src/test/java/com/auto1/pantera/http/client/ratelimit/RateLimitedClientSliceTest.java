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
package com.auto1.pantera.http.client.ratelimit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link RateLimitedClientSlice}: gated requests
 * never reach the wrapped slice; an upstream 429 closes the gate;
 * synthesised 429s carry the marker header.
 *
 * @since 2.2.0
 */
final class RateLimitedClientSliceTest {

    private static final RequestLine GET = new RequestLine(
        RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"
    );

    @Test
    void gatedRequestNeverReachesWrappedSlice() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(
            RateLimitConfig.uniform(100.0, 100.0), clock
        );
        limiter.recordRateLimit("repo1.maven.org", Duration.ofSeconds(10));
        final RecordingSlice downstream = new RecordingSlice();
        final RateLimitedClientSlice slice = new RateLimitedClientSlice(
            downstream, "repo1.maven.org", limiter, clock
        );
        final Response response = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "downstream must not be invoked while gated",
            downstream.calls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "synthesised 429",
            response.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        final List<String> marker = response.headers()
            .values(RateLimitedClientSlice.PANTERA_LIMITED_HEADER);
        MatcherAssert.assertThat(
            "carries Pantera marker header",
            marker, new IsEqual<>(List.of("true"))
        );
    }

    @Test
    void upstream429ClosesTheGate() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(
            RateLimitConfig.uniform(100.0, 100.0), clock
        );
        final AtomicReference<Response> next = new AtomicReference<>(
            ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .build()
        );
        final Slice downstream = (line, headers, body) ->
            CompletableFuture.completedFuture(next.get());
        final RateLimitedClientSlice slice = new RateLimitedClientSlice(
            downstream, "repo1.maven.org", limiter, clock
        );
        MatcherAssert.assertThat(
            "first call passes through",
            slice.response(GET, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        MatcherAssert.assertThat(
            "gate should be closed for ~60 s",
            limiter.gateOpenUntil("repo1.maven.org") != null,
            new IsEqual<>(true)
        );
        // Second call short-circuits — downstream is not consulted.
        next.set(ResponseBuilder.ok().build());
        MatcherAssert.assertThat(
            "while gated even a downstream-200 returns synthesised 429",
            slice.response(GET, Headers.EMPTY, Content.EMPTY).join().status(),
            new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
    }

    @Test
    void emptyBucketSynthesises429WithOneSecondRetryAfter() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(
            RateLimitConfig.uniform(1.0, 1.0), clock
        );
        final Slice downstream = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final RateLimitedClientSlice slice = new RateLimitedClientSlice(
            downstream, "h.example", limiter, clock
        );
        // First call drains the single burst token
        slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        // Second call: bucket empty, synthesise 429
        final Response second = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "second call gets synthesised 429",
            second.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        MatcherAssert.assertThat(
            "Retry-After is at most 1 s for bucket-empty",
            second.headers().values("Retry-After"),
            new IsEqual<>(List.of("1"))
        );
    }

    /** Tracks invocation count + lets the test return canned responses. */
    private static final class RecordingSlice implements Slice {
        final AtomicInteger calls = new AtomicInteger();
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }
    }

    private static final class TestClock extends Clock {
        private Instant now;
        TestClock(final Instant start) {
            this.now = start;
        }
        @Override
        public Instant instant() {
            return this.now;
        }
        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }
    }
}
