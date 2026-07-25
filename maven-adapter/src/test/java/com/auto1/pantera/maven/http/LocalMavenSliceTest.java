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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * WS4-maven.9: HEAD and GET for the same local artifact must agree on
 * {@code Content-Length}, {@code ETag}, {@code Content-Type}, and both must
 * expose {@code Last-Modified}.
 */
final class LocalMavenSliceTest {

    @Test
    @DisplayName("HEAD and GET on the same artifact agree on Content-Length/ETag/Content-Type "
        + "and both expose a valid Last-Modified")
    void headAndGetAgreeOnValidatorsAndExposeLastModified() {
        final String path = "com/example/lib/1.0/lib-1.0.jar";
        final byte[] bytes = "jar-bytes-payload".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        storage.save(
            new Key.From(path + ".sha1"),
            new Content.From("deadbeef".getBytes(StandardCharsets.UTF_8))
        ).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response get = slice.response(
            new RequestLine(RqMethod.GET, "/" + path), Headers.EMPTY, Content.EMPTY
        ).join();
        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, "/" + path), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "GET returns 200", get.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD returns 200", head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD Content-Length equals the stored artifact size",
            head.headers().single("Content-Length").getValue(),
            new IsEqual<>(String.valueOf(bytes.length))
        );
        MatcherAssert.assertThat(
            "HEAD and GET agree on Content-Length",
            head.headers().single("Content-Length").getValue(),
            new IsEqual<>(get.headers().single("Content-Length").getValue())
        );
        MatcherAssert.assertThat(
            "HEAD and GET agree on ETag",
            head.headers().single("ETag").getValue(),
            new IsEqual<>(get.headers().single("ETag").getValue())
        );
        MatcherAssert.assertThat(
            "HEAD and GET agree on Content-Type",
            head.headers().single("Content-Type").getValue(),
            new IsEqual<>(get.headers().single("Content-Type").getValue())
        );
        MatcherAssert.assertThat(
            "HEAD exposes exactly one Last-Modified header",
            head.headers().values("Last-Modified").size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "GET exposes exactly one Last-Modified header",
            get.headers().values("Last-Modified").size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "HEAD's Last-Modified is a valid RFC 1123 HTTP date",
            isValidHttpDate(head.headers().single("Last-Modified").getValue()),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "GET's Last-Modified is a valid RFC 1123 HTTP date",
            isValidHttpDate(get.headers().single("Last-Modified").getValue()),
            new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("HEAD on a missing artifact returns 404, matching GET")
    void headOnMissingArtifactReturns404() {
        final InMemoryStorage storage = new InMemoryStorage();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, "/com/example/missing/1.0/missing-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        final Response get = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/missing/1.0/missing-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "HEAD returns 404 for a missing artifact",
            head.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "GET returns 404 for a missing artifact",
            get.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    /**
     * @param value Candidate {@code Last-Modified} header value
     * @return True when the value parses as an RFC 1123 HTTP date
     */
    private static boolean isValidHttpDate(final String value) {
        try {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(value);
            return true;
        } catch (final DateTimeParseException ex) {
            return false;
        }
    }
}
