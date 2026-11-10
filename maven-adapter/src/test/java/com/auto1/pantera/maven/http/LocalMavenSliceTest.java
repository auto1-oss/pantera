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

    @Test
    @DisplayName("WS4-maven.7: GET's Content-Length matches the actual streamed body length")
    void getContentLengthMatchesBody() {
        final String path = "com/example/lib/2.0/lib-2.0.jar";
        final byte[] bytes = "another-jar-payload".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response get = slice.response(
            new RequestLine(RqMethod.GET, "/" + path), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(get.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "declared Content-Length must match the real body length",
            get.headers().single("Content-Length").getValue(),
            new IsEqual<>(String.valueOf(bytes.length))
        );
        MatcherAssert.assertThat(
            get.body().asBytes().length, new IsEqual<>(bytes.length)
        );
    }

    @Test
    @DisplayName("WS4-maven.7: a GET with a matching If-None-Match returns 304, no body")
    void ifNoneMatchReturns304() {
        final String path = "com/example/lib/3.0/lib-3.0.jar";
        final byte[] bytes = "conditional-jar".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        storage.save(
            new Key.From(path + ".sha1"),
            new Content.From("cafebabe".getBytes(StandardCharsets.UTF_8))
        ).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response conditional = slice.response(
            new RequestLine(RqMethod.GET, "/" + path),
            Headers.from("If-None-Match", "cafebabe"),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "matching If-None-Match must return 304",
            conditional.status(), new IsEqual<>(RsStatus.NOT_MODIFIED)
        );
        MatcherAssert.assertThat(
            "304 must still carry the ETag",
            conditional.headers().single("ETag").getValue(),
            new IsEqual<>("cafebabe")
        );
        MatcherAssert.assertThat(
            "304 must carry no body",
            conditional.body().asBytes().length, new IsEqual<>(0)
        );
    }

    @Test
    @DisplayName("WS4-maven.7: a GET with a stale If-None-Match returns the full 200 body")
    void staleIfNoneMatchReturns200() {
        final String path = "com/example/lib/4.0/lib-4.0.jar";
        final byte[] bytes = "fresh-jar".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        storage.save(
            new Key.From(path + ".sha1"),
            new Content.From("newhash".getBytes(StandardCharsets.UTF_8))
        ).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/" + path),
            Headers.from("If-None-Match", "oldhash"),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "stale If-None-Match must fall through to 200",
            resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            resp.body().asBytes().length, new IsEqual<>(bytes.length)
        );
    }

    @Test
    @DisplayName("WS4-maven.11: a Range request on a real artifact returns 206 with the "
        + "correct byte slice and Content-Range")
    void rangeRequestReturns206WithCorrectSlice() {
        final String path = "com/example/lib/5.0/lib-5.0.jar";
        final byte[] bytes = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/" + path),
            Headers.from("Range", "bytes=2-5"),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Range request on a real artifact must return 206",
            resp.status().code(), new IsEqual<>(206)
        );
        MatcherAssert.assertThat(
            "Content-Range must reflect the requested slice against the full size",
            resp.headers().single("Content-Range").getValue(),
            new IsEqual<>("bytes 2-5/" + bytes.length)
        );
        MatcherAssert.assertThat(
            "body must be exactly the requested byte slice",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("2345")
        );
    }

    @Test
    @DisplayName("WS4-maven.11: a normal (non-Range) GET on a real artifact advertises "
        + "Accept-Ranges: bytes")
    void normalGetAdvertisesAcceptRanges() {
        final String path = "com/example/lib/6.0/lib-6.0.jar";
        final byte[] bytes = "accept-ranges-payload".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path), new Content.From(bytes)).join();
        final LocalMavenSlice slice = new LocalMavenSlice(storage, "test-repo");

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/" + path), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "a real artifact GET must advertise Accept-Ranges: bytes",
            resp.headers().single("Accept-Ranges").getValue(),
            new IsEqual<>("bytes")
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
