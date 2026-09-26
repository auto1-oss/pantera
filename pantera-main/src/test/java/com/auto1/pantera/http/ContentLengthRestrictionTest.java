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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link ContentLengthRestriction}.
 */
class ContentLengthRestrictionTest {

    @Test
    public void shouldNotPassRequestsAboveLimit() {
        final Slice slice = new ContentLengthRestriction(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(), 10
        );
        final Response response = slice.response(new RequestLine("GET", "/"), this.headers("11"), Content.EMPTY)
            .join();
        MatcherAssert.assertThat(response, new RsHasStatus(RsStatus.REQUEST_TOO_LONG));
    }

    @ParameterizedTest
    @CsvSource({"10,0", "10,1", "10,10"})
    public void shouldPassRequestsWithinLimit(int limit, String value) {
        final Slice slice = new ContentLengthRestriction(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(), limit
        );
        final Response response = slice.response(new RequestLine("GET", "/"), this.headers(value), Content.EMPTY)
            .join();
        ResponseAssert.checkOk(response);
    }

    @Test
    public void shouldPassRequestsWithoutContentLength() {
        final Slice slice = new ContentLengthRestriction(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(), 10
        );
        final Response response = slice.response(new RequestLine("GET", "/"), Headers.EMPTY, Content.EMPTY)
            .join();
        ResponseAssert.checkOk(response);
    }

    /**
     * resource-dos F17: a malformed Content-Length used to PASS (the parse
     * failure was treated as "within limit"), so a client could dodge the
     * operator's cap with a non-numeric header.
     */
    @Test
    public void malformedContentLengthIsRejected() {
        final Slice slice = new ContentLengthRestriction(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(), 10
        );
        final Response response = slice.response(
            new RequestLine("PUT", "/"), this.headers("not number"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response, new RsHasStatus(RsStatus.REQUEST_TOO_LONG));
    }

    /**
     * resource-dos F17: the check only ever looked at the declared header,
     * so a chunked body (no Content-Length at all) of ANY size sailed past
     * the operator's cap. Actual bytes must be metered.
     */
    @Test
    public void chunkedBodyExceedingLimitIsRejected() {
        final Slice slice = new ContentLengthRestriction(
            (line, headers, body) -> body.asBytesFuture().thenApply(
                bytes -> ResponseBuilder.ok().build()
            ),
            10
        );
        final byte[] payload = new byte[20];
        // No size hint: exactly what the server builds for chunked framing.
        final Content chunked = new Content.From(
            io.reactivex.Flowable.just(java.nio.ByteBuffer.wrap(payload))
        );
        final Response response = slice.response(
            new RequestLine("PUT", "/"), Headers.EMPTY, chunked
        ).join();
        MatcherAssert.assertThat(response, new RsHasStatus(RsStatus.REQUEST_TOO_LONG));
    }

    private Headers headers(final String value) {
        return Headers.from("Content-Length", value);
    }
}
