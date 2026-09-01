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

import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that an unhandled slice failure produces a safe 500 response and a
 * server-side ERROR record.
 *
 * <p>A 500 body must never carry a Java stack trace: it discloses internal
 * class names, package layout and absolute filesystem paths to any client that
 * can provoke an error. The detail belongs in the log, not the response.</p>
 */
final class VertxSliceServerErrorResponseTest {

    private static final String HOST = "localhost";

    private static final String CAP = "VertxSliceServerErrorCap";

    private static final String LOGGER = "com.auto1.pantera.vertx";

    /**
     * Stand-in for internal detail that must stay server-side. Mirrors the
     * absolute storage path that leaked from a real {@code FileStorage} failure.
     */
    private static final String INTERNAL = "/var/pantera/data/must-not-leak";

    private int port;

    private Vertx vertx;

    private WebClient client;

    private VertxSliceServer server;

    private CapturingAppender capture;

    @BeforeEach
    void setUp() throws Exception {
        this.port = findFreePort();
        this.vertx = Vertx.vertx();
        this.client = WebClient.create(
            this.vertx,
            new WebClientOptions().setConnectTimeout(30000).setIdleTimeout(60)
        );
        this.capture = new CapturingAppender(CAP);
        this.capture.start();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getRootLogger().addAppender(this.capture, null, null);
        cfg.getLoggerConfig(LOGGER).addAppender(this.capture, null, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.getRootLogger().removeAppender(CAP);
        cfg.getLoggerConfig(LOGGER).removeAppender(CAP);
        this.capture.stop();
        ctx.updateLoggers();
        if (this.server != null) {
            try {
                this.server.close();
            } catch (final Exception ignored) {
                // cleanup only
            }
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    @DisplayName("500 body from a failed GET carries no stack trace")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bodyOfFailedGetHidesStackTrace() {
        this.startFailingServer();
        final HttpResponse<Buffer> response =
            this.client.get(this.port, HOST, "/boom").rxSend().blockingGet();
        final String body = response.bodyAsString();
        MatcherAssert.assertThat(
            "failed slice should answer 500",
            response.statusCode(), new IsEqual<>(500)
        );
        assertNoStackTrace(body);
    }

    @Test
    @DisplayName("500 body from a failed POST with a body carries no stack trace")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bodyOfFailedPostHidesStackTrace() {
        this.startFailingServer();
        final HttpResponse<Buffer> response = this.client
            .post(this.port, HOST, "/boom")
            .rxSendBuffer(Buffer.buffer("payload"))
            .blockingGet();
        final String body = response.bodyAsString();
        MatcherAssert.assertThat(
            "failed slice should answer 500 on POST",
            response.statusCode(), new IsEqual<>(500)
        );
        assertNoStackTrace(body);
    }

    @Test
    @DisplayName("Unhandled slice failure is logged at ERROR with the stack trace")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void failureIsLoggedAtErrorLevel() {
        this.startFailingServer();
        this.client.get(this.port, HOST, "/boom").rxSend().blockingGet();
        final LogEvent event = this.capture.awaitError();
        MatcherAssert.assertThat(
            "an ERROR record must exist for a 500",
            event, new IsNot<>(new IsEqual<>(null))
        );
        MatcherAssert.assertThat(
            "record must be at ERROR level",
            event.getLevel(), new IsEqual<>(Level.ERROR)
        );
        MatcherAssert.assertThat(
            "record must retain the internal detail hidden from the client",
            String.valueOf(payload(event, "error.stack_trace")),
            new StringContains(INTERNAL)
        );
    }

    /**
     * Starts a server whose slice always fails with an exception carrying
     * internal detail.
     */
    private void startFailingServer() {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.failedFuture(
                new IllegalStateException(
                    String.format("cannot create directory %s", INTERNAL)
                )
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();
    }

    /**
     * Asserts a response body discloses no stack-trace material.
     *
     * @param body Response body.
     */
    private static void assertNoStackTrace(final String body) {
        MatcherAssert.assertThat(
            "body must not carry stack frames",
            body, new IsNot<>(new StringContains("\tat "))
        );
        MatcherAssert.assertThat(
            "body must not name internal classes",
            body, new IsNot<>(new StringContains("com.auto1.pantera"))
        );
        MatcherAssert.assertThat(
            "body must not disclose internal paths",
            body, new IsNot<>(new StringContains(INTERNAL))
        );
        MatcherAssert.assertThat(
            "body must not name JDK exception types",
            body, new IsNot<>(new StringContains("java.lang."))
        );
    }

    private static Object payload(final LogEvent evt, final String key) {
        final Message msg = evt.getMessage();
        if (msg instanceof MapMessage<?, ?> map) {
            return map.getData().get(key);
        }
        return null;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Collects log events so the test can assert on them.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            synchronized (this.events) {
                this.events.add(event.toImmutable());
            }
        }

        /**
         * Polls for the first ERROR record. The 500 is written to the socket
         * from a different thread than the one that logs, so the record can
         * land just after the client sees the response.
         *
         * @return The ERROR event, or null if none arrived.
         */
        LogEvent awaitError() {
            LogEvent found = null;
            final long deadline = System.currentTimeMillis() + 5000;
            while (found == null && System.currentTimeMillis() < deadline) {
                synchronized (this.events) {
                    for (final LogEvent evt : this.events) {
                        if (evt.getLevel() == Level.ERROR) {
                            found = evt;
                            break;
                        }
                    }
                }
                if (found == null) {
                    try {
                        Thread.sleep(25);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return found;
        }
    }
}
