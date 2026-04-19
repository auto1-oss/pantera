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
 * Tier-1 tests — verifies the {@link StructuredLogger.AccessLogger} emits at
 * the levels dictated by {@link LevelPolicy} and enforces the required
 * RequestContext at entry (§4.3).
 */
final class AccessLoggerTest {

    private static final String CAP = "AccessLoggerCap";
    private static final String LOGGER = "http.access";

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
        final LoggerConfig lconf = cfg.getLoggerConfig(LOGGER);
        lconf.addAppender(this.capture, null, null);
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
    @DisplayName("forRequest(null) throws NullPointerException — required-field guard")
    void forRequestRejectsNullContext() {
        try {
            StructuredLogger.access().forRequest(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(
                expected.getMessage(), Matchers.containsString("ctx")
            );
        }
    }

    @Test
    @DisplayName("2xx → DEBUG per LevelPolicy.CLIENT_FACING_SUCCESS")
    void successLogsAtDebug() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(200).duration(15L).log();
        MatcherAssert.assertThat(this.capture.events, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.DEBUG));
    }

    @Test
    @DisplayName("404 → INFO per LevelPolicy.CLIENT_FACING_NOT_FOUND")
    void notFoundLogsAtInfo() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(404).duration(5L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.INFO));
    }

    @Test
    @DisplayName("401/403 → INFO per LevelPolicy.CLIENT_FACING_UNAUTH")
    void unauthLogsAtInfo() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(401).duration(2L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.INFO));
        StructuredLogger.access().forRequest(minimalCtx())
            .status(403).duration(3L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.INFO));
    }

    @Test
    @DisplayName("400 → WARN (other 4xx still WARN per LevelPolicy.CLIENT_FACING_4XX_OTHER)")
    void other4xxLogsAtWarn() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(400).duration(1L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.WARN));
    }

    @Test
    @DisplayName("5xx → ERROR per LevelPolicy.CLIENT_FACING_5XX")
    void serverErrorLogsAtError() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(503).duration(10L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.ERROR));
    }

    @Test
    @DisplayName(">5000ms slow → WARN per LevelPolicy.CLIENT_FACING_SLOW")
    void slowRequestLogsAtWarn() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(200).duration(6000L).log();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.WARN));
    }

    @Test
    @DisplayName("5xx with Fault.Internal attaches error.type/error.message/error.stack_trace")
    void faultAttachesErrorFields() {
        final Fault.Internal fault = new Fault.Internal(
            new IllegalStateException("boom"),
            "test.where"
        );
        StructuredLogger.access().forRequest(minimalCtx())
            .status(500).fault(fault).duration(200L).log();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payloadField(evt, "error.type"), Matchers.notNullValue());
        MatcherAssert.assertThat(
            (String) payloadField(evt, "error.type"),
            Matchers.containsString("IllegalStateException")
        );
        MatcherAssert.assertThat(payloadField(evt, "error.message"), Matchers.is("boom"));
        MatcherAssert.assertThat(
            (String) payloadField(evt, "error.stack_trace"),
            Matchers.containsString("IllegalStateException")
        );
    }

    @Test
    @DisplayName("RequestContext.bindToMdc() populates trace.id / client.ip during log()")
    void contextBoundToMdcDuringEmit() {
        final RequestContext ctx = new RequestContext(
            "trace-aaa", "txn-xyz", null, null,
            "alice", "10.0.0.1", null,
            "npm_group", "npm", RequestContext.ArtifactRef.EMPTY,
            "/-/all", "/-/all", Deadline.in(Duration.ofSeconds(30))
        );
        StructuredLogger.access().forRequest(ctx)
            .status(404).duration(3L).log();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(
            evt.getContextData().getValue("trace.id"), Matchers.is("trace-aaa")
        );
        MatcherAssert.assertThat(
            evt.getContextData().getValue("client.ip"), Matchers.is("10.0.0.1")
        );
        MatcherAssert.assertThat(
            evt.getContextData().getValue("user.name"), Matchers.is("alice")
        );
        MatcherAssert.assertThat(
            evt.getContextData().getValue("url.original"), Matchers.is("/-/all")
        );
    }

    @Test
    @DisplayName("http.response.status_code and event.duration are top-level MapMessage fields")
    void statusAndDurationInPayload() {
        StructuredLogger.access().forRequest(minimalCtx())
            .status(503).duration(250L).log();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payloadField(evt, "http.response.status_code"), Matchers.is(503));
        MatcherAssert.assertThat(payloadField(evt, "event.duration"), Matchers.is(250L));
    }

    @Test
    @DisplayName("Prior ThreadContext is restored after emission")
    void priorThreadContextIsRestoredAfterLog() {
        ThreadContext.put("pre-existing", "yes");
        StructuredLogger.access().forRequest(minimalCtx())
            .status(200).duration(5L).log();
        MatcherAssert.assertThat(ThreadContext.get("pre-existing"), Matchers.is("yes"));
        MatcherAssert.assertThat(ThreadContext.get("trace.id"), Matchers.nullValue());
    }

    @Test
    @DisplayName("log() parses user_agent.original and emits user_agent.* sub-fields (WI-post-03b)")
    void logEmitsParsedUserAgentSubFields() {
        final RequestContext ctx = new RequestContext(
            "trace-ua", null, null, null,
            "anonymous", "10.0.0.3",
            "Maven/3.9.6 (Java/21.0.3 Linux 6.12.68)",
            "maven_group", "maven", RequestContext.ArtifactRef.EMPTY,
            "/com/example/foo-1.0.jar", "/com/example/foo-1.0.jar",
            Deadline.in(Duration.ofSeconds(10))
        );
        StructuredLogger.access().forRequest(ctx)
            .status(200).duration(42L).log();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payloadField(evt, "user_agent.name"), Matchers.is("Maven"));
        MatcherAssert.assertThat(payloadField(evt, "user_agent.version"), Matchers.is("3.9.6"));
        MatcherAssert.assertThat(payloadField(evt, "user_agent.os.name"), Matchers.is("Linux"));
        MatcherAssert.assertThat(payloadField(evt, "user_agent.os.version"), Matchers.is("21.0.3"));
    }

    @Test
    @DisplayName("log() omits user_agent.* sub-fields when RequestContext.userAgent is null")
    void logSkipsSubFieldsWhenOriginalAbsent() {
        // minimalCtx() has userAgent=null.
        StructuredLogger.access().forRequest(minimalCtx())
            .status(200).duration(3L).log();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payloadField(evt, "user_agent.name"), Matchers.nullValue());
        MatcherAssert.assertThat(payloadField(evt, "user_agent.version"), Matchers.nullValue());
        MatcherAssert.assertThat(payloadField(evt, "user_agent.os.name"), Matchers.nullValue());
        MatcherAssert.assertThat(payloadField(evt, "user_agent.os.version"), Matchers.nullValue());
        MatcherAssert.assertThat(payloadField(evt, "user_agent.device.name"), Matchers.nullValue());
    }

    // ---- helpers ----

    private static RequestContext minimalCtx() {
        return new RequestContext(
            "trace-min", null, null, null, "anonymous", "10.0.0.2", null,
            "repo", "npm", RequestContext.ArtifactRef.EMPTY,
            "/x", "/x", Deadline.in(Duration.ofSeconds(5))
        );
    }

    private static Object payloadField(final LogEvent evt, final String key) {
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
