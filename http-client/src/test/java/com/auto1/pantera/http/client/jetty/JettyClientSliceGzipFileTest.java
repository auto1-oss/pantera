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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.eclipse.jetty.client.HttpClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A gzip file is fetched byte for byte, whatever content-coding the upstream
 * labels it with.
 *
 * <p>A web server configured to announce the encoding of {@code .gz} and
 * {@code .tgz} files (Apache {@code AddEncoding x-gzip .gz .tgz}, an object
 * store whose object carries {@code Content-Encoding: gzip}) sends the stored
 * archive unchanged and labels it {@code Content-Encoding: x-gzip}, or
 * {@code gzip} when the client offers gzip. The client offered gzip on every
 * request and unpacked whatever came back labelled {@code gzip}, so a proxy
 * cached the inner tar under the {@code .tar.gz} name: wrong size, wrong
 * checksum, not a gzip file.</p>
 *
 * @since 2.2.12
 */
final class JettyClientSliceGzipFileTest {

    /**
     * The archive as stored upstream: a gzip stream.
     */
    private static final byte[] ARCHIVE = JettyClientSliceGzipFileTest.archive();

    /**
     * Upstream. The JDK server sends the headers exactly as set; the Vert.x
     * test server rewrites {@code Content-Encoding} of binary types.
     */
    private HttpServer server;

    /**
     * {@code Accept-Encoding} values the upstream received.
     */
    private final List<String> offered = new CopyOnWriteArrayList<>();

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
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.start();
        this.client = new HttpClient();
        this.client.start();
        this.slice = new JettyClientSlice(
            this.client, false, "localhost", this.server.getAddress().getPort(), 0L
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.stop();
        this.server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/download/elinks-0.11.7.tar.gz",
        "/typescript/-/typescript-5.4.5.tgz",
        "/dists/stable/main/binary-amd64/Packages.GZ",
        "/download/elinks-0.11.7.tar.gz?mirror=eu"
    })
    void fetchesGzipFileAsStoredFromServerThatLabelsItsEncoding(final String path)
        throws Exception {
        this.labelLikeApache();
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the upstream answer is relayed", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the body is the archive as stored, not its unpacked content",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(JettyClientSliceGzipFileTest.ARCHIVE)
        );
        MatcherAssert.assertThat(
            "the coding label is dropped: the body is the file, not an encoded form of it",
            response.headers().values("Content-Encoding"), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "Content-Length is the length of the archive",
            response.headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(JettyClientSliceGzipFileTest.ARCHIVE.length)))
        );
        MatcherAssert.assertThat(
            "no compression is asked for a file that is already compressed",
            this.offered, new IsEqual<>(List.of("identity"))
        );
    }

    @Test
    void fetchesGzipFileAsStoredWhenLabelledGzipUnconditionally() throws Exception {
        // An object store serving an object uploaded with Content-Encoding: gzip.
        this.server.createContext(
            "/",
            exchange -> JettyClientSliceGzipFileTest.send(exchange, "application/gzip", "gzip")
        );
        MatcherAssert.assertThat(
            this.slice.response(
                new RequestLine(RqMethod.GET, "/charts/app-1.2.3.tgz"),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(JettyClientSliceGzipFileTest.ARCHIVE)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/org/example/tool/1.0/tool-1.0.tar.gz.sha1",
        "/simple/requests/",
        "/lodash"
    })
    void keepsOfferingGzipForOtherResources(final String path) throws Exception {
        this.labelLikeApache();
        this.slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS).body().asBytesFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            this.offered, new IsEqual<>(List.of("gzip"))
        );
    }

    /**
     * Make the upstream answer every request with the archive, labelled the
     * way Apache's {@code AddEncoding x-gzip} labels a {@code .gz} file:
     * {@code gzip} to a client that offers gzip, {@code x-gzip} otherwise.
     */
    private void labelLikeApache() {
        this.server.createContext(
            "/",
            exchange -> {
                final List<String> accept =
                    exchange.getRequestHeaders().getOrDefault("Accept-Encoding", List.of());
                final String offer = String.join(",", accept);
                this.offered.add(offer);
                JettyClientSliceGzipFileTest.send(
                    exchange, "application/x-gzip", offer.contains("gzip") ? "gzip" : "x-gzip"
                );
            }
        );
    }

    /**
     * Answer 200 with the archive bytes and the given type and coding label.
     * @param exchange Exchange
     * @param type Content-Type
     * @param coding Content-Encoding
     * @throws IOException On error
     */
    private static void send(
        final HttpExchange exchange, final String type, final String coding
    ) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Content-Encoding", coding);
        exchange.sendResponseHeaders(200, JettyClientSliceGzipFileTest.ARCHIVE.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(JettyClientSliceGzipFileTest.ARCHIVE);
        }
    }

    /**
     * A gzip stream of a few hundred kilobytes of compressible data.
     * @return Gzip bytes
     */
    private static byte[] archive() {
        final byte[] tar = new byte[400_000];
        final Random random = new Random(42);
        for (int idx = 0; idx < tar.length; idx += 1) {
            tar[idx] = (byte) ('a' + random.nextInt(4));
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(tar);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }
}
