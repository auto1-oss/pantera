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

import com.auto1.pantera.http.ResponseBuilder;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code Expect: 100-continue} handling of {@link VertxSliceServer}.
 *
 * <p>A client that sends {@code Expect: 100-continue} withholds the body
 * until it sees {@code 100 Continue} (or its own timeout, typically 1-10 s,
 * expires). Bodies below the in-memory buffer threshold used to be buffered
 * BEFORE the interim response was written, so every small upload
 * (e.g. each Maven deploy PUT) waited out the client timeout.</p>
 *
 * @since 2.2.9
 */
final class VertxSliceServerExpectContinueTest {

    /**
     * Upper bound for the socket read. The interim response is written as
     * soon as the headers are parsed; this bound only converts a hang into
     * a failure (it is far above the idle-laptop value of a few ms).
     */
    private static final int READ_TIMEOUT_MS = 20_000;

    private Vertx vertx;

    private VertxSliceServer server;

    private int port;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> body.asBytesFuture().thenApply(
                bytes -> ResponseBuilder.created()
                    .textBody(Integer.toString(bytes.length)).build()
            ),
            new HttpServerOptions().setPort(0).setHost("localhost"),
            Duration.ZERO
        );
        this.port = this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
        this.vertx.close();
    }

    @Test
    @Timeout(60)
    void smallBodyGetsContinueBeforeTheBodyIsSent() throws Exception {
        final String body = "hello, world!";
        try (Socket socket = new Socket("localhost", this.port)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            final OutputStream out = socket.getOutputStream();
            out.write(
                (
                    "PUT /repo/a.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n"
                    + "Expect: 100-continue\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            out.flush();
            final BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            );
            MatcherAssert.assertThat(
                "the interim 100 must arrive before the client sends the body",
                in.readLine(), new StringStartsWith("HTTP/1.1 100")
            );
            // Skip the blank line terminating the interim response.
            in.readLine();
            out.write(body.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            MatcherAssert.assertThat(
                "the final response follows once the body is sent",
                in.readLine(), new StringStartsWith("HTTP/1.1 201")
            );
            final StringBuilder rest = new StringBuilder();
            String line = in.readLine();
            while (line != null) {
                rest.append(line).append('\n');
                line = in.readLine();
            }
            MatcherAssert.assertThat(
                "the slice receives the whole body",
                rest.toString().trim().endsWith("13"), new IsEqual<>(true)
            );
        }
    }
}
