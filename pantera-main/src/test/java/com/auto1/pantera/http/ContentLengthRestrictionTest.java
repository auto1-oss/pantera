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
    @CsvSource({"10,0", "10,not number", "10,1", "10,10"})
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

    private Headers headers(final String value) {
        return Headers.from("Content-Length", value);
    }
}
