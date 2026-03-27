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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.HttpServer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests: {@link JettyClientSlice} must strip {@code Content-Encoding}
 * after Jetty auto-decodes compressed response bodies.
 *
 * <p>Root cause: Jetty's {@code GZIPContentDecoder} (registered by default) decodes
 * gzip response bodies but leaves the original {@code Content-Encoding: gzip} header
 * intact in {@code response.getHeaders()}. Passing this header through to callers
 * creates a header/body mismatch: the body contains plain bytes while the header
 * still claims it is gzip-compressed. Any HTTP client that trusts the header will
 * attempt to inflate the plain bytes and fail with {@code Z_DATA_ERROR}.
 *
 * <p>Fix: {@link JettyClientSlice#toHeaders} detects decoded encodings and strips both
 * {@code Content-Encoding} and {@code Content-Length} (which refers to compressed size).
 *
 * @since 1.20.13
 */
final class JettyClientSliceGzipTest {

    /**
     * Test server.
     */
    private final HttpServer server = new HttpServer();

    /**
     * Jetty HTTP client.
     */
    private HttpClient client;

    /**
     * Slice under test.
     */
    private JettyClientSlice slice;

    @BeforeEach
    void setUp() throws Exception {
        final int port = this.server.start();
        this.client = new HttpClient();
        this.client.start();
        this.slice = new JettyClientSlice(this.client, false, "localhost", port, 0L);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.server.stop();
        this.client.stop();
    }

    @Test
    void stripsContentEncodingGzipFromResponse() throws Exception {
        final String body = "hello world from gzip";
        final byte[] compressed = gzip(body.getBytes(StandardCharsets.UTF_8));
        this.server.update(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "text/plain")
                    .header("Content-Length", String.valueOf(compressed.length))
                    .body(compressed)
                    .build()
            )
        );
        final var response = this.slice.response(
            new RequestLine(RqMethod.GET, "/test"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "Content-Encoding must be stripped after Jetty auto-decodes gzip body"
        );
        assertEquals(
            body,
            response.body().asString(),
            "Response body must be decoded (plain text)"
        );
    }

    @Test
    void stripsContentEncodingGzipForJsonResponse() throws Exception {
        // Uses application/json (a compressible type that VertxSliceServer won't override)
        // to verify that Content-Encoding is stripped and Content-Length is absent after decoding.
        final String body = "{\"key\":\"compressed json payload\"}";
        final byte[] compressed = gzip(body.getBytes(StandardCharsets.UTF_8));
        this.server.update(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/json")
                    .header("Content-Length", String.valueOf(compressed.length))
                    .body(compressed)
                    .build()
            )
        );
        final var response = this.slice.response(
            new RequestLine(RqMethod.GET, "/api/data.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "Content-Encoding must be stripped after Jetty decodes gzip"
        );
        assertFalse(
            hasHeader(response.headers(), "Content-Length"),
            "Content-Length (compressed size) must be stripped after gzip decode"
        );
        assertEquals(body, response.body().asString(), "Body must be decoded plain text");
    }

    @Test
    void preservesOtherHeadersWhenGzipStripped() throws Exception {
        final byte[] compressed = gzip("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
        this.server.update(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/json")
                    .header("ETag", "\"abc123\"")
                    .header("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")
                    .body(compressed)
                    .build()
            )
        );
        final var response = this.slice.response(
            new RequestLine(RqMethod.GET, "/meta.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get();
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "Content-Encoding must be stripped"
        );
        assertEquals(
            "application/json",
            firstHeader(response.headers(), "Content-Type"),
            "Content-Type must be preserved"
        );
        assertEquals(
            "\"abc123\"",
            firstHeader(response.headers(), "ETag"),
            "ETag must be preserved"
        );
    }

    @Test
    void doesNotStripWhenNoContentEncoding() {
        final String body = "plain response";
        this.server.update(
            (line, headers, content) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "text/plain")
                    .textBody(body)
                    .build()
            )
        );
        final var response = this.slice.response(
            new RequestLine(RqMethod.GET, "/plain"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "No Content-Encoding header expected on plain response"
        );
        // Note: Vert.x may append charset to Content-Type (e.g. "text/plain; charset=utf-8")
        final String contentType = firstHeader(response.headers(), "Content-Type");
        org.junit.jupiter.api.Assertions.assertNotNull(contentType, "Content-Type must be present");
        org.junit.jupiter.api.Assertions.assertTrue(
            contentType.startsWith("text/plain"),
            "Content-Type must start with text/plain, was: " + contentType
        );
        assertEquals(body, response.body().asString(), "Body must be unchanged");
    }

    private static byte[] gzip(final byte[] input) throws Exception {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    private static boolean hasHeader(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .anyMatch(h -> name.equalsIgnoreCase(h.getKey()));
    }

    private static String firstHeader(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> name.equalsIgnoreCase(h.getKey()))
            .map(com.auto1.pantera.http.headers.Header::getValue)
            .findFirst()
            .orElse(null);
    }
}
