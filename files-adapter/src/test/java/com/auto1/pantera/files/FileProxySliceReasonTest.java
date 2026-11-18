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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import java.net.ConnectException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The file proxy answers 404 whenever it cannot serve a file, whatever the
 * cause. The reason on the answer tells the causes apart: the upstream has
 * no such file, the upstream failed, or the call to it did.
 *
 * @since 2.2.12
 */
final class FileProxySliceReasonTest {

    @Test
    @Timeout(20)
    void aNotFoundOfTheUpstreamIsNamedAsSuch() throws Exception {
        MatcherAssert.assertThat(
            FileProxySliceReasonTest.reason(new SliceSimple(ResponseBuilder.notFound().build())),
            new IsEqual<>(Optional.of("upstream_status_404"))
        );
    }

    @Test
    @Timeout(20)
    void aNotFoundThatStandsForAnUpstreamErrorSaysSo() throws Exception {
        MatcherAssert.assertThat(
            FileProxySliceReasonTest.reason(
                new SliceSimple(ResponseBuilder.internalError().build())
            ),
            new IsEqual<>(Optional.of("upstream_status_500"))
        );
    }

    @Test
    @Timeout(20)
    void aNotFoundThatStandsForAFailedCallSaysHowItFailed() throws Exception {
        MatcherAssert.assertThat(
            FileProxySliceReasonTest.reason(
                (line, headers, body) ->
                    CompletableFuture.failedFuture(new ConnectException("Connection refused"))
            ),
            new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    /**
     * The reason of the 404 a file proxy over this upstream answers.
     * @param upstream Upstream
     * @return Reason
     * @throws Exception On error
     */
    private static Optional<String> reason(final Slice upstream) throws Exception {
        final Response answer = new ReasonSlice(
            new FileProxySlice(upstream, new StreamThroughCache(new InMemoryStorage()))
        ).response(
            new RequestLine(RqMethod.GET, "/a/file.bin"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the proxy answers 404", answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        return new AnswerReason(answer).token();
    }
}
