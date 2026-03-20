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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link HeadProxySlice}.
 */
class HeadProxySliceTest {

    @Test
    void performsRequestWithEmptyHeaderAndBody() {
        new HeadProxySlice(new SliceSimple(ResponseBuilder.ok().build())).response(
            RequestLine.from("HEAD /some/path HTTP/1.1"),
            Headers.from("some", "value"),
            new Content.From("000".getBytes())
        ).thenAccept(resp -> {
            Assertions.assertTrue(resp.headers().isEmpty());
            Assertions.assertEquals(0, resp.body().asBytes().length);
        });
    }

    @Test
    void passesStatusAndHeadersFromResponse() {
        final Headers headers = Headers.from("abc", "123");
        MatcherAssert.assertThat(
            new HeadProxySlice(
                new SliceSimple(ResponseBuilder.created().header("abc", "123").build())
            ),
            new SliceHasResponse(
                Matchers.allOf(new RsHasStatus(RsStatus.CREATED), new RsHasHeaders(headers)),
                new RequestLine(RqMethod.HEAD, "/")
            )
        );
    }

}
