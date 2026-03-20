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
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseMatcher;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AuthClientSlice}.
 */
class AuthClientSliceTest {

    @Test
    void shouldNotModifyRequestAndResponseIfNoAuthRequired() {
        final RequestLine line = new RequestLine(RqMethod.GET, "/file.txt");
        final Header header = new Header("x-name", "some value");
        final byte[] body = "text".getBytes();
        final Response response = new AuthClientSlice(
            (rsline, rsheaders, rsbody) -> {
                if (!rsline.equals(line)) {
                    throw new IllegalArgumentException(String.format("Line modified: %s", rsline));
                }
                return ResponseBuilder.ok()
                    .headers(rsheaders)
                    .body(rsbody)
                    .completedFuture();
            },
            Authenticator.ANONYMOUS
        ).response(line, Headers.from(header), new Content.From(body)).join();
        MatcherAssert.assertThat(
            response,
            new ResponseMatcher(RsStatus.OK, body, header)
        );
    }
}
