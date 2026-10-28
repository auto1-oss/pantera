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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ServerErrorLog}: the cause record of a synthesised 5xx carries the
 * ECS cause fields, the request's trace.id (even on a thread whose MDC holds
 * another request's id, which it leaves untouched) and the unwrapped error.
 *
 * @since 2.2.11
 */
final class ServerErrorLogTest {

    /**
     * Logger under test.
     */
    private static final String LOGGER = "com.auto1.pantera.test.server-error";

    /**
     * Trace id of the request.
     */
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    /**
     * Capture.
     */
    private Capture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
        this.capture = Capture.of(LOGGER);
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        ThreadContext.clearMap();
    }

    @Test
    void throwableRecordCarriesUnwrappedCauseAndRequestTrace() {
        ThreadContext.put(EcsMdc.TRACE_ID, "stale-trace-of-another-request");
        new ServerErrorLog(LOGGER, "proxy_upstream", ServerErrorLogTest.headers())
            .repository("maven-central")
            .path("/org/x/1.0/x-1.0.jar")
            .upstream("https://user:secret@repo1.maven.org/maven2/org/x/1.0/x-1.0.jar")
            .status(502)
            .failure(new CompletionException(new ConnectException("Connection refused")))
            .warn("Upstream fetch of primary artifact failed");
        MatcherAssert.assertThat(
            "one record", this.capture.events.size(), new IsEqual<>(1)
        );
        final Map<String, Object> rec = this.capture.events.get(0);
        MatcherAssert.assertThat(
            "level", rec.get("level"), new IsEqual<>(Level.WARN)
        );
        MatcherAssert.assertThat(
            "reason from the cause",
            rec.get("event.reason"), new IsEqual<>("connection_refused")
        );
        MatcherAssert.assertThat(
            "error.type unwrapped",
            rec.get("error.type"), new IsEqual<>("java.net.ConnectException")
        );
        MatcherAssert.assertThat(
            "stack trace keeps the wrapper chain",
            String.valueOf(rec.get("error.stack_trace")),
            new StringContains("CompletionException")
        );
        MatcherAssert.assertThat(
            "status", rec.get("http.response.status_code"), new IsEqual<>(502)
        );
        MatcherAssert.assertThat(
            "message names status and cause",
            rec.get("message"),
            new IsEqual<>(
                "Upstream fetch of primary artifact failed; answering 502"
                    + " (cause: connection_refused)"
            )
        );
        MatcherAssert.assertThat(
            "destination host", rec.get("destination.address"),
            new IsEqual<>("repo1.maven.org")
        );
        MatcherAssert.assertThat(
            "url credentials masked",
            String.valueOf(rec.get("url.full")).contains("secret"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "trace.id of the request wins over the stale thread MDC",
            rec.get("trace.id"), new IsEqual<>(TRACE)
        );
        MatcherAssert.assertThat(
            "thread MDC restored after the record",
            ThreadContext.get(EcsMdc.TRACE_ID), new IsEqual<>("stale-trace-of-another-request")
        );
        MatcherAssert.assertThat(
            "client.ip bound only for the record",
            ThreadContext.get(EcsMdc.CLIENT_IP), new IsNull<>()
        );
    }

    @Test
    void upstreamStatusRecordHasNoErrorFields() {
        new ServerErrorLog(LOGGER, "proxy_upstream", ServerErrorLogTest.headers())
            .status(502)
            .upstreamStatus(503)
            .warn("Upstream answered 503");
        final Map<String, Object> rec = this.capture.events.get(0);
        MatcherAssert.assertThat(
            "reason", rec.get("event.reason"), new IsEqual<>("upstream_status_503")
        );
        MatcherAssert.assertThat(
            "no stack trace", rec.get("error.stack_trace"), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "outcome", rec.get("event.outcome"), new IsEqual<>("failure")
        );
        MatcherAssert.assertThat(
            "category without an upstream URL", rec.get("event.category"),
            new IsEqual<>(List.of("web"))
        );
    }

    @Test
    void errorLevelForInternalFailures() {
        new ServerErrorLog(LOGGER, "artifact_upload", Headers.EMPTY)
            .status(500)
            .failure(new IllegalStateException("boom"))
            .error("Upload failed");
        final Map<String, Object> rec = this.capture.events.get(0);
        MatcherAssert.assertThat(
            "level", rec.get("level"), new IsEqual<>(Level.ERROR)
        );
        MatcherAssert.assertThat(
            "reason", rec.get("event.reason"), new IsEqual<>("internal_error")
        );
    }

    @Test
    void expectedPerRequestOutcomesAreDebug() {
        new ServerErrorLog(LOGGER, "proxy_upstream", Headers.EMPTY)
            .status(502).reason("circuit_open").warnUnlessExpected("open");
        new ServerErrorLog(LOGGER, "proxy_upstream", Headers.EMPTY)
            .status(502).failure(new UpstreamCircuitOpenException(3L))
            .warnUnlessExpected("open, from the throwable");
        new ServerErrorLog(LOGGER, "bulkhead_dispatch", Headers.EMPTY)
            .status(503).reason("bulkhead_full").warnUnlessExpected("full");
        new ServerErrorLog(LOGGER, "proxy_upstream", Headers.EMPTY)
            .status(502).upstreamStatus(503).warnUnlessExpected("503");
        MatcherAssert.assertThat(
            "circuit_open (explicit) at DEBUG",
            this.capture.events.get(0).get("level"), new IsEqual<>(Level.DEBUG)
        );
        MatcherAssert.assertThat(
            "circuit_open (derived) at DEBUG",
            this.capture.events.get(1).get("level"), new IsEqual<>(Level.DEBUG)
        );
        MatcherAssert.assertThat(
            "bulkhead_full at DEBUG",
            this.capture.events.get(2).get("level"), new IsEqual<>(Level.DEBUG)
        );
        MatcherAssert.assertThat(
            "an upstream status stays WARN",
            this.capture.events.get(3).get("level"), new IsEqual<>(Level.WARN)
        );
    }

    @Test
    void errorSummaryHasNoStackTrace() {
        EcsLogger.warn(LOGGER)
            .message("summary")
            .errorSummary(new CompletionException(new ConnectException("refused")))
            .log();
        final Map<String, Object> rec = this.capture.events.get(0);
        MatcherAssert.assertThat(
            "type unwrapped", rec.get("error.type"),
            new IsEqual<>("java.net.ConnectException")
        );
        MatcherAssert.assertThat(
            "message", rec.get("error.message"), new IsEqual<>("refused")
        );
        MatcherAssert.assertThat(
            "no stack trace", rec.get("error.stack_trace"), new IsNull<>()
        );
    }

    @Test
    void classifiesCommonUpstreamFailures() {
        final Map<Throwable, String> cases = new HashMap<>();
        cases.put(new UnknownHostException("nowhere.example"), "unknown_host");
        cases.put(new SocketTimeoutException("Connect timed out"), "connect_timeout");
        cases.put(new SocketTimeoutException("Read timed out"), "read_timeout");
        cases.put(new TimeoutException("Idle timeout expired: 30000/30000 ms"), "idle_timeout");
        cases.put(new TimeoutException("Total timeout 60000 ms elapsed"), "request_timeout");
        cases.put(new javax.net.ssl.SSLHandshakeException("PKIX path"), "tls_error");
        cases.put(
            new RejectedExecutionException("Max requests queued per destination 1024 exceeded"),
            "request_rejected"
        );
        cases.put(new IOException("Connection reset by peer"), "connection_closed");
        cases.put(new IOException("disk full"), "io_error");
        cases.put(new UpstreamCircuitOpenException(5L), "circuit_open");
        cases.put(
            new CompletionException(new IllegalStateException("x", new ConnectException("r"))),
            "connection_refused"
        );
        for (final Map.Entry<Throwable, String> entry : cases.entrySet()) {
            MatcherAssert.assertThat(
                entry.getKey().toString(),
                new FailureCause(entry.getKey()).reason(), new IsEqual<>(entry.getValue())
            );
        }
    }

    private static Headers headers() {
        return new Headers().add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE);
    }

    /**
     * Appender capturing payload + context data of one logger.
     */
    private static final class Capture extends AbstractAppender implements AutoCloseable {

        /**
         * Events.
         */
        private final List<Map<String, Object>> events =
            Collections.synchronizedList(new ArrayList<>());

        /**
         * Logger.
         */
        private final Logger logger;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("ServerErrorLogTest", null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                final Map<String, Object> data = new HashMap<>();
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
                data.putAll(event.getContextData().toMap());
                data.put("level", event.getLevel());
                this.events.add(data);
            }
        }

        @Override
        public void close() {
            this.logger.removeAppender(this);
            this.stop();
        }
    }
}
