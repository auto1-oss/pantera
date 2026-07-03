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
package com.auto1.pantera.http.observability;

import com.auto1.pantera.http.context.Deadline;
import com.auto1.pantera.http.context.RequestContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-4 tests — {@link StructuredLogger.LocalLogger} for local ops
 * (DB, cache, pool-init, queue-drop, ...).
 */
final class LocalLoggerTest {

    private static final String CAP = "LocalLoggerCap";
    private static final String COMPONENT = "com.auto1.pantera.test.local";

    private CapturingAppender capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.capture = new CapturingAppender(CAP);
        this.capture.start();
        final LoggerContext lc = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = lc.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getRootLogger().addAppender(this.capture, null, null);
        cfg.getLoggerConfig(COMPONENT).addAppender(this.capture, null, null);
        lc.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext lc = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = lc.getConfiguration();
        cfg.getRootLogger().removeAppender(CAP);
        cfg.getLoggerConfig(COMPONENT).removeAppender(CAP);
        this.capture.stop();
        lc.updateLoggers();
        ThreadContext.clearAll();
    }

    @Test
    @DisplayName("forComponent(null) throws NPE")
    void forComponentRejectsNull() {
        try {
            StructuredLogger.local().forComponent(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("component"));
        }
    }

    @Test
    @DisplayName("info() for config change → INFO")
    void configChangeLogsAtInfo() {
        StructuredLogger.local().forComponent(COMPONENT)
            .message("Pool init: 16 threads, queue=2000").info();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.INFO));
    }

    @Test
    @DisplayName("debug() for op-success → DEBUG")
    void opSuccessLogsAtDebug() {
        StructuredLogger.local().forComponent(COMPONENT).message("ok").debug();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.DEBUG));
    }

    @Test
    @DisplayName("warn() for degraded → WARN")
    void degradedLogsAtWarn() {
        StructuredLogger.local().forComponent(COMPONENT)
            .message("executor queue at 90% — caller-runs applied")
            .field("pantera.queue.size", 1800)
            .warn();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.WARN));
        MatcherAssert.assertThat(
            payload(this.capture.last(), "pantera.queue.size"), Matchers.is(1800)
        );
    }

    @Test
    @DisplayName("error() without cause throws NPE")
    void errorWithoutCauseFails() {
        try {
            StructuredLogger.local().forComponent(COMPONENT).message("msg").error();
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("cause"));
        }
    }

    @Test
    @DisplayName("error() with cause → ERROR with error.type / error.stack_trace")
    void errorWithCauseLogsAtError() {
        final Exception cause = new java.io.IOException("disk full");
        StructuredLogger.local().forComponent(COMPONENT)
            .message("flush failed")
            .cause(cause)
            .error();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(evt.getLevel(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(
            (String) payload(evt, "error.type"),
            Matchers.containsString("IOException")
        );
        MatcherAssert.assertThat(
            (String) payload(evt, "error.stack_trace"),
            Matchers.containsString("disk full")
        );
    }

    @Test
    @DisplayName("reqCtx binds trace.id for request-linked local ops")
    void reqCtxBindsTraceId() {
        final RequestContext ctx = new RequestContext(
            "trace-loc", null, null, null, "anonymous", null, null,
            "repo", "npm", RequestContext.ArtifactRef.EMPTY,
            "/x", "/x", Deadline.in(Duration.ofSeconds(5))
        );
        StructuredLogger.local().forComponent(COMPONENT)
            .message("cache evict")
            .reqCtx(ctx)
            .debug();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(
            evt.getContextData().getValue("trace.id"), Matchers.is("trace-loc")
        );
    }

    @Test
    @DisplayName("Terminal without message() throws IllegalStateException")
    void missingMessageFails() {
        try {
            StructuredLogger.local().forComponent(COMPONENT).debug();
            MatcherAssert.assertThat("expected ISE", false, Matchers.is(true));
        } catch (final IllegalStateException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("message"));
        }
    }

    // ---- helpers ----

    private static Object payload(final LogEvent evt, final String key) {
        final Message msg = evt.getMessage();
        if (msg instanceof MapMessage<?, ?> mm) {
            return mm.getData().get(key);
        }
        return null;
    }

    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            this.events.add(event.toImmutable());
        }

        LogEvent last() {
            return this.events.get(this.events.size() - 1);
        }
    }
}
