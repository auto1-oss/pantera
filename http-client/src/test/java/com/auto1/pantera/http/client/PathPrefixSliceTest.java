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
package com.auto1.pantera.http.client;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link PathPrefixSlice}.
 */
final class PathPrefixSliceTest {

    @ParameterizedTest
    @CsvSource({
        "'',/,/,",
        "/prefix,/,/prefix/,",
        "/a/b/c,/d/e/f,/a/b/c/d/e/f,",
        "/my/repo,/123/file.txt?param1=foo&param2=bar,/my/repo/123/file.txt,param1=foo&param2=bar",
        "/aaa/bbb,/%26/file.txt?p=%20%20,/aaa/bbb/%26/file.txt,p=%20%20"
    })
    void shouldAddPrefixToPathAndPreserveEverythingElse(
        final String prefix, final String line, final String path, final String query
    ) {
        final RqMethod method = RqMethod.GET;
        final Headers headers = Headers.from("X-Header", "The Value");
        final byte[] body = "request body".getBytes();
        new PathPrefixSlice(
            (rsline, rqheaders, rqbody) -> {
                MatcherAssert.assertThat(
                    "Path is modified",
                    rsline.uri().getRawPath(),
                    new IsEqual<>(path)
                );
                MatcherAssert.assertThat(
                    "Query is preserved",
                    rsline.uri().getRawQuery(),
                    new IsEqual<>(query)
                );
                MatcherAssert.assertThat(
                    "Method is preserved",
                    rsline.method(),
                    new IsEqual<>(method)
                );
                MatcherAssert.assertThat(
                    "Headers are preserved",
                    rqheaders,
                    new IsEqual<>(headers)
                );
                MatcherAssert.assertThat(
                    "Body is preserved",
                    new Content.From(rqbody).asBytesFuture().toCompletableFuture().join(),
                    new IsEqual<>(body)
                );
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            },
            prefix
        ).response(new RequestLine(method, line), headers, new Content.From(body)).join();
    }
}
