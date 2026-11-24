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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.UpstreamStatusException;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestReasons;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link UpstreamMiss}.
 *
 * @since 2.2.14
 */
final class UpstreamMissTest {

    @ParameterizedTest
    @CsvSource({
        "404,404,false",
        "410,404,false",
        "401,404,true",
        "403,404,true",
        "400,404,true",
        "429,503,false",
        "500,502,false",
        "502,502,false",
        "503,502,false",
        "504,502,false"
    })
    void mapsTheUpstreamStatus(final int upstream, final int answered, final boolean skip) {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY)
            .answered(upstream, Headers.EMPTY);
        MatcherAssert.assertThat(
            "status answered for upstream " + upstream,
            answer.status().code(), new IsEqual<>(answered)
        );
        MatcherAssert.assertThat(
            "the reason is the upstream status",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("upstream_status_" + upstream))
        );
        MatcherAssert.assertThat(
            "only a 404 that is not the upstream's own is kept out of negative caches",
            !answer.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(skip)
        );
    }

    @Test
    void relaysRetryAfterOfAThrottlingUpstream() {
        MatcherAssert.assertThat(
            UpstreamMissTest.miss(Headers.EMPTY)
                .answered(429, Headers.from("Retry-After", "12"))
                .headers().values("Retry-After"),
            new IsEqual<>(List.of("12"))
        );
    }

    @Test
    void anUnavailableUpstreamThatSaysWhenToRepeatIsUnavailable() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY)
            .answered(503, Headers.from("Retry-After", "5"));
        MatcherAssert.assertThat(
            "503 with Retry-After stays a 503",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "with its Retry-After",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("5"))
        );
    }

    @Test
    void keepsTheCircuitOpenMarker() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY).answered(
            502,
            Headers.from(UpstreamCircuitOpenException.HEADER, "true").copy()
                .add("Retry-After", "30")
        );
        MatcherAssert.assertThat(
            "the refusal is still recognisable",
            new LocalRefusal(answer).present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the breaker's Retry-After",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("30"))
        );
        MatcherAssert.assertThat(
            "and the reason is the breaker, not the status",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("circuit_open"))
        );
    }

    @Test
    void keepsTheBulkheadMarker() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY).answered(
            503, Headers.from(LocalRefusal.BULKHEAD, "true")
        );
        MatcherAssert.assertThat(
            "the refusal is a bulkhead refusal",
            new LocalRefusal(answer).bulkhead(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "it stays a 503",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "and says so",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("bulkhead_full"))
        );
    }

    @Test
    void aFailedCallIsBadGatewayWithItsCause() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY).failed(
            new CompletionException(new ConnectException("Connection refused")), "fetch failed"
        );
        MatcherAssert.assertThat(
            "a failed call is a 502", answer.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "with the transport failure as its reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    void aTimeoutIsNotNotFound() {
        MatcherAssert.assertThat(
            UpstreamMissTest.miss(Headers.EMPTY)
                .failed(new TimeoutException("Total timeout 30000 ms elapsed"), "fetch failed")
                .status(),
            new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
    }

    @Test
    void restoresTheMarkerFromACircuitOpenException() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY).failed(
            new CompletionException(new UpstreamCircuitOpenException(17L)), "fetch failed"
        );
        MatcherAssert.assertThat(
            "the refusal is recognisable",
            new LocalRefusal(answer).present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "with the breaker's Retry-After",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("17"))
        );
    }

    @Test
    void anUpstreamAnswerCarriedAsAnExceptionIsMappedAsThatAnswer() {
        final Response answer = UpstreamMissTest.miss(Headers.EMPTY).failed(
            new CompletionException(
                new UpstreamStatusException(429, Headers.from("Retry-After", "9"))
            ),
            "fetch failed"
        );
        MatcherAssert.assertThat(
            "429 carried as an exception is a 503",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "with its Retry-After",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("9"))
        );
        MatcherAssert.assertThat(
            "and the upstream status as its reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("upstream_status_429"))
        );
    }

    @Test
    void notesTheReasonForTheAccessRecord() {
        final String slot = RequestReasons.instance().open();
        UpstreamMissTest.miss(Headers.from(RequestReasons.SLOT_HEADER, slot))
            .answered(503, Headers.EMPTY);
        MatcherAssert.assertThat(
            RequestReasons.instance().close(slot),
            new IsEqual<>(Optional.of("upstream_status_503"))
        );
    }

    private static UpstreamMiss miss(final Headers request) {
        return new UpstreamMiss("com.auto1.pantera.test", "repo", "/a/b", request);
    }
}
