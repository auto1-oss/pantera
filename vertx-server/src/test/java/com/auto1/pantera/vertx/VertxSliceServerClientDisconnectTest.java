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
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.reactivex.core.Vertx;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A download that ends early leaves exactly one record.
 *
 * <p>When the client goes away in the middle of a body that is its own
 * business: one {@code WARN} with {@code event.reason=client_disconnected},
 * the request's trace id, method and path and how far the body got. One
 * aborted npm download used to leave four {@code ERROR}s and a
 * "End has already been called" {@code WARN} under three trace ids. When
 * the body fails on the server side (storage, upstream) that is one
 * {@code ERROR}. Neither case tries to end the response a second time.</p>
 *
 * @since 2.2.14
 */
@Timeout(120)
final class VertxSliceServerClientDisconnectTest {

    private static final String HOST = "localhost";

    private static final String CAP = "ClientDisconnectCap";

    /**
     * First part of the body; the rest never comes.
     */
    private static final int SENT = 4096;

    private static final int DECLARED = 1_000_000;

    private Vertx vertx;

    private VertxSliceServer server;

    private int port;

    private Capture capture;

    /**
     * Root level before the test.
     */
    private Level before;

    @BeforeEach
    void setUp() {
        // One event loop: a request served after the aborted one proves the
        // server has finished everything it does for the aborted one.
        this.vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        this.capture = new Capture(CAP);
        this.capture.start();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getRootLogger().addAppender(this.capture, null, null);
        // The default test configuration logs errors only.
        this.before = cfg.getRootLogger().getLevel();
        cfg.getRootLogger().setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().removeAppender(CAP);
        ctx.getConfiguration().getRootLogger().setLevel(this.before);
        this.capture.stop();
        ctx.updateLoggers();
        if (this.server != null) {
            this.server.close();
        }
        this.vertx.close();
    }

    @Test
    void clientCloseDuringSizedBodyIsOneWarning() throws Exception {
        this.start(true, false, new HttpServerOptions().setPort(0).setHost(HOST));
        final String trace = this.abortHttp1Download();
        final Map<String, Object> warn = this.capture.awaitReason("client_disconnected");
        this.settle();
        this.assertSingleDisconnectRecord(warn, trace);
        MatcherAssert.assertThat(
            "the record says how far the body got",
            String.valueOf(warn.get("message")),
            new StringContains(String.format("of %d bytes", DECLARED))
        );
    }

    @Test
    void clientCloseDuringChunkedBodyIsOneWarning() throws Exception {
        this.start(false, false, new HttpServerOptions().setPort(0).setHost(HOST));
        final String trace = this.abortHttp1Download();
        final Map<String, Object> warn = this.capture.awaitReason("client_disconnected");
        this.settle();
        this.assertSingleDisconnectRecord(warn, trace);
    }

    @Test
    void clientResetOfHttp2StreamIsOneWarning() throws Exception {
        this.start(
            true, false,
            new HttpServerOptions().setPort(0).setHost(HOST).setHttp2ClearTextEnabled(true)
        );
        final HttpClient client = this.vertx.getDelegate().createHttpClient(
            new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
        );
        try {
            final CompletableFuture<String> trace = new CompletableFuture<>();
            client.request(HttpMethod.GET, this.port, HOST, "/npm-local/pkg/-/pkg-5.4.0.tgz")
                .onFailure(trace::completeExceptionally)
                .onSuccess(
                    req -> req.send()
                        .onFailure(trace::completeExceptionally)
                        .onSuccess(
                            rsp -> rsp.handler(
                                chunk -> {
                                    // First bytes arrived: give up the stream.
                                    trace.complete(
                                        VertxSliceServerClientDisconnectTest.traceOf(
                                            rsp.getHeader("traceparent")
                                        )
                                    );
                                    req.reset();
                                }
                            )
                        )
                );
            final String sent = trace.get(30, TimeUnit.SECONDS);
            final Map<String, Object> warn = this.capture.awaitReason("client_disconnected");
            this.settle();
            this.assertSingleDisconnectRecord(warn, sent);
        } finally {
            client.close();
        }
    }

    @Test
    void serverSideBodyFailureIsOneError() throws Exception {
        this.start(true, true, new HttpServerOptions().setPort(0).setHost(HOST));
        try (Socket socket = new Socket(HOST, this.port)) {
            socket.setSoTimeout(30_000);
            VertxSliceServerClientDisconnectTest.get(socket, "/npm-local/pkg/-/pkg-5.4.0.tgz");
            final InputStream input = socket.getInputStream();
            final byte[] buf = new byte[4096];
            int read = input.read(buf);
            while (read >= 0) {
                read = input.read(buf);
            }
        }
        final Map<String, Object> error = this.capture.awaitLevel(Level.ERROR);
        this.settle();
        MatcherAssert.assertThat(
            "exactly one ERROR for a body that failed on the server side",
            this.capture.atLevel(Level.ERROR).size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "it is the record of the broken body",
            error.get("event.action"), new IsEqual<>("response_stream")
        );
        MatcherAssert.assertThat(
            "it names the cause",
            error.get("event.reason"), new IsEqual<>("io_error")
        );
        MatcherAssert.assertThat(
            "it names the request",
            error.get("url.path"), new IsEqual<>("/npm-local/pkg/-/pkg-5.4.0.tgz")
        );
        MatcherAssert.assertThat(
            "nothing tried to end the response a second time",
            this.capture.messagesContaining("End has already been called"),
            new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "a server-side failure is not blamed on the client",
            this.capture.reason("client_disconnected"), new IsEqual<>(List.of())
        );
    }

    /**
     * One WARN, no ERROR, no duplicate-end warning, the request's identity.
     * @param warn The disconnect record
     * @param trace Trace id the response carried
     */
    private void assertSingleDisconnectRecord(
        final Map<String, Object> warn, final String trace
    ) {
        MatcherAssert.assertThat(
            "a client that goes away is no server error",
            this.capture.atLevel(Level.ERROR), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "exactly one record of the disconnect",
            this.capture.reason("client_disconnected").size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and it is the only warning",
            this.capture.atLevel(Level.WARN).size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "nothing tried to end the response a second time",
            this.capture.messagesContaining("End has already been called"),
            new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "the record is at WARN", warn.get("level"), new IsEqual<>(Level.WARN)
        );
        MatcherAssert.assertThat(
            "it carries the trace id of its own request",
            warn.get("trace.id"), new IsEqual<>(trace)
        );
        MatcherAssert.assertThat(
            "it names the path",
            warn.get("url.path"), new IsEqual<>("/npm-local/pkg/-/pkg-5.4.0.tgz")
        );
        MatcherAssert.assertThat(
            "it names the method",
            warn.get("http.request.method"), new IsEqual<>("GET")
        );
        MatcherAssert.assertThat(
            "it says how many bytes were written",
            warn.get("http.response.body.bytes"), new IsEqual<>((long) SENT)
        );
    }

    /**
     * Start a download over HTTP/1.1, read the head and the first bytes,
     * then close the connection.
     * @return Trace id of the response's {@code traceparent}
     * @throws IOException On socket failure
     */
    private String abortHttp1Download() throws IOException {
        final String head;
        try (Socket socket = new Socket(HOST, this.port)) {
            socket.setSoTimeout(30_000);
            VertxSliceServerClientDisconnectTest.get(socket, "/npm-local/pkg/-/pkg-5.4.0.tgz");
            final InputStream input = socket.getInputStream();
            final StringBuilder seen = new StringBuilder();
            final byte[] buf = new byte[1024];
            while (seen.indexOf("\r\n\r\n") < 0) {
                final int read = input.read(buf);
                if (read < 0) {
                    throw new IOException("connection closed before the head arrived");
                }
                seen.append(new String(buf, 0, read, StandardCharsets.ISO_8859_1));
            }
            head = seen.substring(0, seen.indexOf("\r\n\r\n"));
        }
        String trace = null;
        for (final String line : head.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("traceparent:")) {
                trace = VertxSliceServerClientDisconnectTest.traceOf(
                    line.substring(line.indexOf(':') + 1).trim()
                );
            }
        }
        return trace;
    }

    /**
     * Serve one more request. With a single event loop its answer proves
     * that everything the server logs for the earlier exchange is written.
     * @throws IOException On socket failure
     */
    private void settle() throws IOException {
        try (Socket socket = new Socket(HOST, this.port)) {
            socket.setSoTimeout(30_000);
            VertxSliceServerClientDisconnectTest.get(socket, "/.health");
            if (socket.getInputStream().read() < 0) {
                throw new IOException("no answer to the settling request");
            }
        }
    }

    /**
     * Start a server answering 200 with {@link #SENT} bytes followed by
     * either nothing (the body stays open) or a failure.
     * @param sized Declare a Content-Length of {@link #DECLARED}
     * @param failing Fail the body after the first bytes
     * @param options Server options
     */
    private void start(
        final boolean sized, final boolean failing, final HttpServerOptions options
    ) {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final Flowable<ByteBuffer> first = Flowable.just(ByteBuffer.wrap(new byte[SENT]));
                final Flowable<ByteBuffer> rest;
                if (failing) {
                    rest = Flowable.error(new IOException("upstream closed"));
                } else {
                    rest = Flowable.never();
                }
                final ResponseBuilder builder = ResponseBuilder.ok();
                if (sized) {
                    builder.header("Content-Length", String.valueOf(DECLARED));
                }
                return CompletableFuture.completedFuture(
                    builder.body(new Content.From(first.concatWith(rest))).build()
                );
            },
            options
        );
        this.port = this.server.start();
    }

    private static void get(final Socket socket, final String path) throws IOException {
        socket.getOutputStream().write(
            String.format("GET %s HTTP/1.1\r\nHost: %s\r\n\r\n", path, HOST)
                .getBytes(StandardCharsets.ISO_8859_1)
        );
        socket.getOutputStream().flush();
    }

    private static String traceOf(final String traceparent) {
        return traceparent == null ? null : traceparent.split("-")[1];
    }

    /**
     * Captures every record at WARN or above, payload plus context data.
     */
    private static final class Capture extends AbstractAppender {

        private final List<Map<String, Object>> events =
            Collections.synchronizedList(new ArrayList<>());

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (!event.getLevel().isMoreSpecificThan(Level.WARN)) {
                return;
            }
            final Map<String, Object> data = new HashMap<>();
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
            } else {
                data.put("message", event.getMessage().getFormattedMessage());
            }
            data.putAll(event.getContextData().toMap());
            data.put("level", event.getLevel());
            this.events.add(data);
        }

        List<Map<String, Object>> atLevel(final Level level) {
            return this.select(event -> level.equals(event.get("level")));
        }

        List<Map<String, Object>> reason(final String token) {
            return this.select(event -> token.equals(event.get("event.reason")));
        }

        List<Map<String, Object>> messagesContaining(final String text) {
            return this.select(event -> String.valueOf(event.get("message")).contains(text));
        }

        Map<String, Object> awaitReason(final String token) {
            return this.await(event -> token.equals(event.get("event.reason")));
        }

        Map<String, Object> awaitLevel(final Level level) {
            return this.await(event -> level.equals(event.get("level")));
        }

        private List<Map<String, Object>> select(
            final java.util.function.Predicate<Map<String, Object>> filter
        ) {
            final List<Map<String, Object>> out = new ArrayList<>();
            synchronized (this.events) {
                for (final Map<String, Object> event : this.events) {
                    if (filter.test(event)) {
                        out.add(event);
                    }
                }
            }
            return out;
        }

        /**
         * Poll for the first matching record: it is written by the event
         * loop when it notices the closed connection.
         */
        private Map<String, Object> await(
            final java.util.function.Predicate<Map<String, Object>> filter
        ) {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                final List<Map<String, Object>> found = this.select(filter);
                if (!found.isEmpty()) {
                    return found.get(0);
                }
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("no matching record within 30 s: " + this.events);
        }
    }
}
