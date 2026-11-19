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
package com.auto1.pantera.vertx;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.ResponseBuilder;
import io.reactivex.Flowable;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.reactivex.core.Vertx;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.hamcrest.core.StringEndsWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A response body that fails after the head was sent, as it does when the
 * upstream of a proxy closes its connection in the middle of a download.
 * The status and the headers are with the client already, so the only way
 * left to say "this body is not complete" is to break off the exchange.
 * Ending it normally leaves an HTTP/1.1 client with a {@code Content-Length}
 * waiting for bytes that never come, and shows any other client a truncated
 * body as a complete one.
 *
 * @since 2.2.12
 */
final class VertxSliceServerTruncatedBodyTest {

    /**
     * Host of the server under test.
     */
    private static final String HOST = "localhost";

    /**
     * Bytes of the body that arrive before it fails.
     */
    private static final int SENT = 100;

    /**
     * Length the response declares.
     */
    private static final int DECLARED = 1000;

    /**
     * How long a client waits for the server to act. The server reacts to
     * the failure within milliseconds; the bound only turns an exchange left
     * open into a failed test.
     */
    private static final int WAIT_MS = 15_000;

    /**
     * Vert.x instance.
     */
    private Vertx vertx;

    /**
     * Server under test.
     */
    private VertxSliceServer server;

    /**
     * Port the server listens on.
     */
    private int port;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        this.vertx.close();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void closesTheConnectionWhenABodyWithContentLengthFails() throws Exception {
        this.start(true, new HttpServerOptions().setPort(0).setHost(HOST));
        final String raw = this.exchange();
        MatcherAssert.assertThat(
            "the head announces the full length",
            raw.toLowerCase(Locale.ROOT),
            new StringContains(String.format("content-length: %d", DECLARED))
        );
        MatcherAssert.assertThat(
            "only the bytes sent before the failure arrive",
            raw.length() - raw.indexOf("\r\n\r\n") - 4, new IsEqual<>(SENT)
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void doesNotCompleteAChunkedBodyThatFails() throws Exception {
        this.start(false, new HttpServerOptions().setPort(0).setHost(HOST));
        final String raw = this.exchange();
        MatcherAssert.assertThat(
            "the response is chunked",
            raw.toLowerCase(Locale.ROOT), new StringContains("transfer-encoding: chunked")
        );
        MatcherAssert.assertThat(
            "the terminating chunk is not sent",
            raw, new IsNot<>(new StringEndsWith("0\r\n\r\n"))
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void resetsTheHttp2StreamWhenABodyFails() throws Exception {
        this.start(
            true,
            new HttpServerOptions().setPort(0).setHost(HOST).setHttp2ClearTextEnabled(true)
        );
        final HttpClient client = this.vertx.getDelegate().createHttpClient(
            new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
        );
        try {
            final CompletableFuture<Integer> body = new CompletableFuture<>();
            client.request(HttpMethod.GET, this.port, HOST, "/file")
                .compose(req -> req.send())
                .compose(rsp -> rsp.body())
                .onSuccess(buf -> body.complete(buf.length()))
                .onFailure(body::completeExceptionally);
            Assertions.assertThrows(
                ExecutionException.class,
                () -> body.get(WAIT_MS, TimeUnit.MILLISECONDS),
                "the body must fail for the client, not end short"
            );
        } finally {
            client.close();
        }
    }

    /**
     * Starts a server that answers 200 with a body of {@link #SENT} bytes
     * followed by a failure.
     *
     * @param sized Whether the response declares {@link #DECLARED} bytes
     * @param options Server options
     */
    private void start(final boolean sized, final HttpServerOptions options) {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final Content failing = new Content.From(
                    Flowable.just(ByteBuffer.wrap(new byte[SENT]))
                        .concatWith(Flowable.error(new IOException("upstream closed")))
                );
                final ResponseBuilder builder = ResponseBuilder.ok();
                if (sized) {
                    builder.header("Content-Length", String.valueOf(DECLARED));
                }
                return CompletableFuture.completedFuture(builder.body(failing).build());
            },
            options
        );
        this.port = this.server.start();
    }

    /**
     * Sends one GET over a kept-alive HTTP/1.1 connection and reads until
     * the server closes it.
     *
     * @return Everything the server sent, one char per byte
     * @throws IOException When the server leaves the connection open
     */
    private String exchange() throws IOException {
        try (Socket socket = new Socket(HOST, this.port)) {
            socket.setSoTimeout(WAIT_MS);
            socket.getOutputStream().write(
                String.format("GET /file HTTP/1.1\r\nHost: %s\r\n\r\n", HOST)
                    .getBytes(StandardCharsets.ISO_8859_1)
            );
            socket.getOutputStream().flush();
            final ByteArrayOutputStream all = new ByteArrayOutputStream();
            final InputStream input = socket.getInputStream();
            final byte[] buf = new byte[4096];
            int read = input.read(buf);
            while (read >= 0) {
                all.write(buf, 0, read);
                read = input.read(buf);
            }
            return all.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
