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
import com.auto1.pantera.http.fault.Fault;
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
import org.apache.logging.log4j.core.config.LoggerConfig;
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
 * Tier-2 tests — {@link StructuredLogger.InternalLogger} fires only on 500s
 * from internal callees. {@code error()} requires a {@link Fault} (500-only).
 */
final class InternalLoggerTest {

    private static final String CAP = "InternalLoggerCap";
    private static final String LOGGER = "http.internal";

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
        cfg.getLoggerConfig(LOGGER).addAppender(this.capture, null, null);
        lc.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext lc = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = lc.getConfiguration();
        cfg.getRootLogger().removeAppender(CAP);
        cfg.getLoggerConfig(LOGGER).removeAppender(CAP);
        this.capture.stop();
        lc.updateLoggers();
        ThreadContext.clearAll();
    }

    @Test
    @DisplayName("forCall(null, m) throws NPE")
    void forCallRejectsNullCtx() {
        try {
            StructuredLogger.internal().forCall(null, "member");
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(expected.getMessage(), Matchers.containsString("ctx"));
        }
    }

    @Test
    @DisplayName("forCall(ctx, null) throws NPE")
    void forCallRejectsNullMember() {
        try {
            StructuredLogger.internal().forCall(ctx(), null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(expected.getMessage(), Matchers.containsString("memberName"));
        }
    }

    @Test
    @DisplayName("error() without fault throws IllegalStateException")
    void errorWithoutFaultFails() {
        try {
            StructuredLogger.internal().forCall(ctx(), "member").error();
            MatcherAssert.assertThat("expected ISE", false, Matchers.is(true));
        } catch (final IllegalStateException expected) {
            MatcherAssert.assertThat(
                expected.getMessage(), Matchers.containsString("Fault")
            );
        }
    }

    @Test
    @DisplayName("fault(null) throws NPE")
    void faultRejectsNull() {
        try {
            StructuredLogger.internal().forCall(ctx(), "member").fault(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(expected.getMessage(), Matchers.containsString("fault"));
        }
    }

    @Test
    @DisplayName("500 Fault.Internal → ERROR with error.type / error.message / error.stack_trace")
    void internalFaultEmitsAtErrorWithStack() {
        final Fault.Internal fault = new Fault.Internal(
            new RuntimeException("db-boom"), "idx.lookup"
        );
        StructuredLogger.internal().forCall(ctx(), "npm_proxy")
            .fault(fault).error();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(evt.getLevel(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(payload(evt, "error.type"), Matchers.notNullValue());
        MatcherAssert.assertThat(
            (String) payload(evt, "error.type"),
            Matchers.containsString("RuntimeException")
        );
        MatcherAssert.assertThat(payload(evt, "error.message"), Matchers.is("db-boom"));
        MatcherAssert.assertThat(
            (String) payload(evt, "error.stack_trace"),
            Matchers.containsString("db-boom")
        );
        MatcherAssert.assertThat(payload(evt, "internal.target"), Matchers.is("npm_proxy"));
    }

    @Test
    @DisplayName("debug() for successful internal calls — DEBUG level")
    void debugHookLogsAtDebug() {
        StructuredLogger.internal().forCall(ctx(), "hosted").debug();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.DEBUG));
    }

    // ---- helpers ----

    private static RequestContext ctx() {
        return new RequestContext(
            "trace-int", null, null, null, "anonymous", null, null,
            "grp", "npm", RequestContext.ArtifactRef.EMPTY,
            "/x", "/x", Deadline.in(Duration.ofSeconds(5))
        );
    }

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
