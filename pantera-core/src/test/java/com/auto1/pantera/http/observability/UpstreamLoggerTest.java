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
 * Tier-3 tests — {@link StructuredLogger.UpstreamLogger} for pantera → upstream.
 */
final class UpstreamLoggerTest {

    private static final String CAP = "UpstreamLoggerCap";
    private static final String LOGGER = "http.upstream";

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
    @DisplayName("forUpstream(null, _, _) throws NPE")
    void forUpstreamRejectsNullCtx() {
        try {
            StructuredLogger.upstream().forUpstream(null, "h", 443);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("ctx"));
        }
    }

    @Test
    @DisplayName("forUpstream(ctx, null, _) throws NPE")
    void forUpstreamRejectsNullAddress() {
        try {
            StructuredLogger.upstream().forUpstream(ctx(), null, 443);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(
                ex.getMessage(), Matchers.containsString("destinationAddress")
            );
        }
    }

    @Test
    @DisplayName(".error() without cause throws IllegalStateException")
    void errorWithoutCauseFails() {
        try {
            StructuredLogger.upstream().forUpstream(ctx(), "h", 443)
                .responseStatus(502).error();
            MatcherAssert.assertThat("expected ISE", false, Matchers.is(true));
        } catch (final IllegalStateException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("cause"));
        }
    }

    @Test
    @DisplayName("cause(null) throws NPE")
    void causeRejectsNull() {
        try {
            StructuredLogger.upstream().forUpstream(ctx(), "h", 443).cause(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("cause"));
        }
    }

    @Test
    @DisplayName("5xx + cause → ERROR with destination.address / destination.port / duration")
    void serverErrorLogsAtErrorWithDestinationFields() {
        final Exception cause = new java.net.ConnectException("connect refused");
        StructuredLogger.upstream()
            .forUpstream(ctx(), "registry.npmjs.org", 443)
            .responseStatus(502)
            .duration(1250L)
            .cause(cause)
            .error();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(evt.getLevel(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(payload(evt, "destination.address"), Matchers.is("registry.npmjs.org"));
        MatcherAssert.assertThat(payload(evt, "destination.port"), Matchers.is(443));
        MatcherAssert.assertThat(payload(evt, "http.response.status_code"), Matchers.is(502));
        MatcherAssert.assertThat(payload(evt, "event.duration"), Matchers.is(1250L));
        MatcherAssert.assertThat(
            (String) payload(evt, "error.type"),
            Matchers.containsString("ConnectException")
        );
    }

    @Test
    @DisplayName("2xx → DEBUG per LevelPolicy.UPSTREAM_SUCCESS")
    void successLogsAtDebug() {
        StructuredLogger.upstream().forUpstream(ctx(), "host", 80).responseStatus(200).debug();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.DEBUG));
    }

    @Test
    @DisplayName("404 via debug() → DEBUG per LevelPolicy.UPSTREAM_NOT_FOUND")
    void notFoundLogsAtDebug() {
        StructuredLogger.upstream().forUpstream(ctx(), "host", 80).responseStatus(404).debug();
        MatcherAssert.assertThat(this.capture.last().getLevel(), Matchers.is(Level.DEBUG));
    }

    // ---- helpers ----

    private static RequestContext ctx() {
        return new RequestContext(
            "trace-up", null, null, null, "anonymous", null, null,
            "npm_proxy", "npm", RequestContext.ArtifactRef.EMPTY,
            "/lodash", "/lodash", Deadline.in(Duration.ofSeconds(5))
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
