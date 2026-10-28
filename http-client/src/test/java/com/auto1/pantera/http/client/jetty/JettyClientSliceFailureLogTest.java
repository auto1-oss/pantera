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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.eclipse.jetty.client.HttpClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A failed upstream call leaves one correlated WARN line — cause, URL, host,
 * trace.id of the calling request — without a stack trace: the slice that
 * answers the client logs the throwable with its stack.
 *
 * @since 2.2.11
 */
final class JettyClientSliceFailureLogTest {

    /**
     * Trace id bound on the calling thread.
     */
    private static final String TRACE = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    /**
     * Jetty client.
     */
    private HttpClient client;

    /**
     * Capture.
     */
    private Capture capture;

    @BeforeEach
    void setUp() throws Exception {
        this.client = new HttpClient();
        this.client.start();
        this.capture = Capture.of("com.auto1.pantera.http.client");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.capture.close();
        this.client.stop();
        ThreadContext.clearMap();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void refusedConnectionLogsOneLineWithoutStack() throws Exception {
        final int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        final JettyClientSlice slice = new JettyClientSlice(
            this.client, false, "localhost", port, 0L
        );
        ThreadContext.put("trace.id", TRACE);
        try {
            slice.response(
                new RequestLine(RqMethod.GET, "/maven2/a/b/1.0/b-1.0.jar"),
                Headers.EMPTY, Content.EMPTY
            ).join();
            MatcherAssert.assertThat("request must fail", false, new IsEqual<>(true));
        } catch (final CompletionException expected) {
            // the failure under test
        }
        ThreadContext.remove("trace.id");
        final List<Map<String, Object>> records = this.capture.action("http_request_send");
        MatcherAssert.assertThat("one record", records.size(), new IsEqual<>(1));
        final Map<String, Object> rec = records.get(0);
        MatcherAssert.assertThat("WARN", rec.get("level"), new IsEqual<>(Level.WARN));
        MatcherAssert.assertThat(
            "cause", rec.get("event.reason"), new IsEqual<>("connection_refused")
        );
        MatcherAssert.assertThat(
            "error.type", String.valueOf(rec.get("error.type")),
            new StringContains("ConnectException")
        );
        MatcherAssert.assertThat(
            "no stack trace", rec.get("error.stack_trace"), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "upstream URL", String.valueOf(rec.get("url.full")),
            new StringContains("localhost:" + port + "/maven2/a/b/1.0/b-1.0.jar")
        );
        MatcherAssert.assertThat(
            "host", rec.get("destination.address"), new IsEqual<>("localhost")
        );
        MatcherAssert.assertThat(
            "trace.id of the calling request", rec.get("trace.id"), new IsEqual<>(TRACE)
        );
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
         * Level before.
         */
        private final Level before;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("JettyClientSliceFailureLogTest", null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.before = logger.getLevel();
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        List<Map<String, Object>> action(final String action) {
            final List<Map<String, Object>> out = new ArrayList<>();
            synchronized (this.events) {
                for (final Map<String, Object> event : this.events) {
                    if (action.equals(String.valueOf(event.get("event.action")))
                        && "failure".equals(String.valueOf(event.get("event.outcome")))) {
                        out.add(event);
                    }
                }
            }
            return out;
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
            this.logger.setLevel(this.before);
            this.stop();
        }
    }
}
