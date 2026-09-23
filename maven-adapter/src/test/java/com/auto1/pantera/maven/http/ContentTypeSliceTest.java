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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link ContentTypeSlice}: proxied Maven files get the same
 * Content-Type as locally served ones, an upstream type is kept.
 *
 * @since 2.2.9
 */
final class ContentTypeSliceTest {

    @ParameterizedTest
    @CsvSource({
        "/g/a/1.0/a-1.0.module,,application/json",
        "/g/a/1.0/a-1.0.jar.sha1,,text/plain",
        "/g/a/1.0/a-1.0.jar,,application/java-archive",
        "/g/a/maven-metadata.xml,application/xml; charset=utf-8,application/xml; charset=utf-8"
    })
    void typesSuccessfulResponses(final String path, final String upstream, final String type) {
        MatcherAssert.assertThat(
            new ContentTypeSlice(
                (line, headers, body) -> {
                    final ResponseBuilder builder = ResponseBuilder.ok().body(new byte[1]);
                    if (upstream != null) {
                        builder.header("Content-Type", upstream);
                    }
                    return builder.completedFuture();
                }
            ).response(
                new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
            ).join().headers().values("Content-Type"),
            new IsEqual<>(List.of(type))
        );
    }

    @Test
    void keepsTheContentLengthOfAHeadResponse() {
        final Response resp = new ContentTypeSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                new Response(
                    RsStatus.OK,
                    Headers.from(new ContentLength(1234)),
                    Content.EMPTY
                )
            )
        ).response(
            new RequestLine(RqMethod.HEAD, "/g/a/1.0/a-1.0.jar"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Content-Length of the HEAD answer is kept",
            resp.headers().values("Content-Length"),
            new IsEqual<>(List.of("1234"))
        );
        MatcherAssert.assertThat(
            "Content-Type is added",
            resp.headers().values("Content-Type"),
            new IsEqual<>(List.of("application/java-archive"))
        );
    }

    @Test
    void keepsTheContentLengthOfACachedHeadProxyHit() {
        final byte[] bytes = "cached-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From("g/a/1.0/a-1.0.jar"), new Content.From(bytes)).join();
        MatcherAssert.assertThat(
            new ContentTypeSlice(
                new HeadProxySlice(
                    (line, headers, body) -> CompletableFuture.failedFuture(
                        new AssertionError("upstream must not be hit on cache HEAD")
                    ),
                    Optional.of(storage)
                )
            ).response(
                new RequestLine(RqMethod.HEAD, "/g/a/1.0/a-1.0.jar"),
                Headers.EMPTY, Content.EMPTY
            ).join().headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(bytes.length)))
        );
    }
}
