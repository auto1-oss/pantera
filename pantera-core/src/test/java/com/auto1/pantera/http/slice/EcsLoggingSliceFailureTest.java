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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the access record {@link EcsLoggingSlice} emits when the wrapped slice
 * fails.
 *
 * <p>A failed request must still carry {@code http.response.status_code}. Without
 * it the record is invisible to every 5xx query and dashboard built on the access
 * log, so a server-side outage reads as zero errors.</p>
 */
final class EcsLoggingSliceFailureTest {

    private static final String CAP = "EcsLoggingSliceFailureCap";

    private static final String LOGGER = "http.access";

    private CapturingAppender capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
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
        ThreadContext.clearAll();
    }

    @Test
    @DisplayName("A failed request logs an access record carrying status 500 at ERROR")
    void failedRequestLogsStatusAtErrorLevel() {
        final Slice failing = (line, headers, body) ->
            CompletableFuture.failedFuture(new IllegalStateException("storage down"));
        final EcsLoggingSlice slice = new EcsLoggingSlice(failing);
        slice.response(
            new RequestLine("GET", "/repo/some/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).handle((resp, err) -> null).join();
        final LogEvent event = this.capture.firstWithStatus();
        MatcherAssert.assertThat(
            "a failed request must produce an access record with a status code",
            event, new IsNot<>(new IsEqual<>(null))
        );
        MatcherAssert.assertThat(
            "status must be recorded as 500",
            payload(event, "http.response.status_code"), new IsEqual<>(500)
        );
        MatcherAssert.assertThat(
            "a 500 access record must be emitted at ERROR",
            event.getLevel(), new IsEqual<>(Level.ERROR)
        );
        MatcherAssert.assertThat(
            "the record must mark the request as failed",
            payload(event, "event.outcome"), new IsEqual<>("failure")
        );
    }

    private static Object payload(final LogEvent evt, final String key) {
        final Message msg = evt.getMessage();
        if (msg instanceof MapMessage<?, ?> map) {
            return map.getData().get(key);
        }
        return null;
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
         * @return First captured record that carries a status code, or null.
         */
        LogEvent firstWithStatus() {
            LogEvent found = null;
            synchronized (this.events) {
                for (final LogEvent evt : this.events) {
                    if (payload(evt, "http.response.status_code") != null) {
                        found = evt;
                        break;
                    }
                }
            }
            return found;
        }
    }
}
