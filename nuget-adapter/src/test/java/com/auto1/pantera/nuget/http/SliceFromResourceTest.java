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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link SliceFromResource}.
 */
public class SliceFromResourceTest {

    @Test
    void shouldDelegateGetResponse() {
        final Header header = new Header("Name", "Value");
        final byte[] body = "body".getBytes();
        final Response response = new SliceFromResource(
            new Resource() {
                @Override
                public CompletableFuture<Response> get(final Headers headers) {
                    return ResponseBuilder.ok().headers(headers)
                        .body(body).completedFuture();
                }

                @Override
                public CompletableFuture<Response> put(Headers headers, Content body) {
                    throw new UnsupportedOperationException();
                }
            }
        ).response(
            new RequestLine(RqMethod.GET, "/some/path"),
            Headers.from(Collections.singleton(header)),
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertArrayEquals(body, response.body().asBytes());
        MatcherAssert.assertThat(
            response.headers(),
            Matchers.containsInRelativeOrder(header)
        );
    }

    @Test
    void shouldDelegatePutResponse() {
        final Header header = new Header("X-Name", "Something");
        final byte[] content = "content".getBytes();
        final Response response = new SliceFromResource(
            new Resource() {
                @Override
                public CompletableFuture<Response> get(Headers headers) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<Response> put(Headers headers, Content body) {
                    return ResponseBuilder.ok().headers(headers)
                        .body(body).completedFuture();
                }
            }
        ).response(
            new RequestLine(RqMethod.PUT, "/some/other/path"),
            Headers.from(Collections.singleton(header)),
            new Content.From(content)
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertArrayEquals(content, response.body().asBytes());
        MatcherAssert.assertThat(
            response.headers(),
            Matchers.containsInRelativeOrder(header)
        );
    }
}
