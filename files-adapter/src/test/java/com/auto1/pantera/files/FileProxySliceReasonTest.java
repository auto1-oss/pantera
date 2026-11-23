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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The file proxy answers 404 only when the upstream said that it has no such
 * file. An upstream that failed, or that could not be reached, is a 5xx: a
 * 404 would let a group remember the file as absent for longer than the
 * outage lasts. The reason on the answer names the cause.
 *
 * @since 2.2.12
 */
final class FileProxySliceReasonTest {

    @Test
    @Timeout(20)
    void aNotFoundOfTheUpstreamIsNamedAsSuch() throws Exception {
        final Response answer = FileProxySliceReasonTest.answer(
            new SliceSimple(ResponseBuilder.notFound().build())
        );
        MatcherAssert.assertThat(
            "the proxy answers 404", answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the upstream's own 404 is the reason",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("upstream_status_404"))
        );
        MatcherAssert.assertThat(
            "the upstream's own 404 may be negative-cached",
            answer.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(20)
    void anUpstreamErrorIsBadGatewayNotNotFound() throws Exception {
        final Response answer = FileProxySliceReasonTest.answer(
            new SliceSimple(ResponseBuilder.internalError().build())
        );
        MatcherAssert.assertThat(
            "an upstream 500 is not a missing file",
            answer.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("upstream_status_500"))
        );
    }

    @Test
    @Timeout(20)
    void aFailedCallIsBadGatewayAndSaysHowItFailed() throws Exception {
        final Response answer = FileProxySliceReasonTest.answer(
            (line, headers, body) ->
                CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            "a refused connection is not a missing file",
            answer.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    @Timeout(20)
    void anUpstreamRefusalIsANotFoundThatIsNeverRemembered() throws Exception {
        final Response answer = FileProxySliceReasonTest.answer(
            new SliceSimple(ResponseBuilder.unauthorized().build())
        );
        MatcherAssert.assertThat(
            "an upstream 401 concerns Pantera's credentials: the client gets 404",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("upstream_status_401"))
        );
        MatcherAssert.assertThat(
            "a group must not negative-cache it",
            answer.headers().values(NegativeCache.SKIP_HEADER),
            new IsEqual<>(List.of("true"))
        );
    }

    @Test
    @Timeout(20)
    void anOpenCircuitKeepsItsMarker() throws Exception {
        final Response answer = FileProxySliceReasonTest.answer(
            new SliceSimple(
                ResponseBuilder.badGateway()
                    .header(UpstreamCircuitOpenException.HEADER, "true")
                    .header("Retry-After", "30").build()
            )
        );
        MatcherAssert.assertThat(
            "a group must be able to skip this member: the refusal is marked",
            new LocalRefusal(answer).present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the breaker's Retry-After is relayed",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("30"))
        );
        MatcherAssert.assertThat(
            "the open breaker is the reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("circuit_open"))
        );
    }

    /**
     * What a file proxy over this upstream answers.
     * @param upstream Upstream
     * @return Answer, with its reason
     * @throws Exception On error
     */
    private static Response answer(final Slice upstream) throws Exception {
        final Response answer = new ReasonSlice(
            new FileProxySlice(upstream, new StreamThroughCache(new InMemoryStorage()))
        ).response(
            new RequestLine(RqMethod.GET, "/a/file.bin"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        answer.body().asBytesFuture().get(10, TimeUnit.SECONDS);
        return answer;
    }
}
