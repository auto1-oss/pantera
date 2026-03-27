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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.hm.AssertSlice;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.hm.RqHasHeader;
import com.auto1.pantera.http.hm.RqLineHasUri;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Test case for {@link TrimPathSlice}.
 * @since 0.8
 */
final class TrimPathSliceTest {

    @Test
    void changesOnlyUriPath() {
        new TrimPathSlice(
            new AssertSlice(
                new RqLineHasUri(
                    new IsEqual<>(URI.create("http://www.w3.org/WWW/TheProject.html"))
                )
            ),
            "pub/"
        ).response(requestLine("http://www.w3.org/pub/WWW/TheProject.html"),
            Headers.EMPTY, Content.EMPTY).join();
    }

    @Test
    void failIfUriPathDoesntMatch() throws Exception {
        ResponseAssert.check(
            new TrimPathSlice((line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.ok().build()), "none")
                .response(requestLine("http://www.w3.org"), Headers.EMPTY, Content.EMPTY)
                .join(),
            RsStatus.INTERNAL_ERROR
        );
    }

    @Test
    void replacesFirstPartOfAbsoluteUriPath() {
        new TrimPathSlice(
            new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath("/three"))),
            "/one/two/"
        ).response(requestLine("/one/two/three"), Headers.EMPTY, Content.EMPTY).join();
    }

    @Test
    void replaceFullUriPath() {
        final String path = "/foo/bar";
        new TrimPathSlice(
            new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath("/"))),
            path
        ).response(requestLine(path), Headers.EMPTY, Content.EMPTY).join();
    }

    @Test
    void appendsFullPathHeaderToRequest() {
        final String path = "/a/b/c";
        new TrimPathSlice(
            new AssertSlice(
                Matchers.anything(),
                new RqHasHeader.Single("x-fullpath", path),
                Matchers.anything()
            ),
            "/a/b"
        ).response(requestLine(path), Headers.EMPTY, Content.EMPTY).join();
    }

    @Test
    void trimPathByPattern() {
        final String path = "/repo/version/artifact";
        new TrimPathSlice(
            new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath("/version/artifact"))),
            Pattern.compile("/[a-zA-Z0-9]+/")
        ).response(requestLine(path), Headers.EMPTY, Content.EMPTY).join();
    }

    @Test
    void dontTrimTwice() {
        final String prefix = "/one";
        new TrimPathSlice(
            new TrimPathSlice(
                new AssertSlice(
                    new RqLineHasUri(new RqLineHasUri.HasPath("/one/two"))
                ),
                prefix
            ),
            prefix
        ).response(requestLine("/one/one/two"), Headers.EMPTY, Content.EMPTY).join();
    }

    private static RequestLine requestLine(final String path) {
        return new RequestLine("GET", path, "HTTP/1.1");
    }
}
