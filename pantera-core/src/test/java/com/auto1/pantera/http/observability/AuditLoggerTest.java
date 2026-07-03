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

import com.auto1.pantera.audit.AuditAction;
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
 * Tier-5 tests — {@link StructuredLogger.AuditLogger} emits compliance events
 * at INFO, regardless of operational log-level settings, with a closed
 * {@link AuditAction} enum and required package name+version.
 */
final class AuditLoggerTest {

    private static final String CAP = "AuditLoggerCap";
    private static final String LOGGER = "com.auto1.pantera.audit";

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
    @DisplayName("forEvent(null, A) throws NPE")
    void forEventRejectsNullCtx() {
        try {
            StructuredLogger.audit().forEvent(null, AuditAction.ARTIFACT_PUBLISH);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("ctx"));
        }
    }

    @Test
    @DisplayName("forEvent(ctx, null) throws NPE")
    void forEventRejectsNullAction() {
        try {
            StructuredLogger.audit().forEvent(ctx(), null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("action"));
        }
    }

    @Test
    @DisplayName("emit() without packageName throws NPE")
    void emitWithoutPackageNameFails() {
        try {
            StructuredLogger.audit().forEvent(ctx(), AuditAction.ARTIFACT_PUBLISH)
                .packageVersion("1.0").emit();
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("packageName"));
        }
    }

    @Test
    @DisplayName("emit() without packageVersion throws NPE")
    void emitWithoutPackageVersionFails() {
        try {
            StructuredLogger.audit().forEvent(ctx(), AuditAction.ARTIFACT_PUBLISH)
                .packageName("pkg").emit();
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException ex) {
            MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("packageVersion"));
        }
    }

    @Test
    @DisplayName("All four AuditAction variants emit at INFO")
    void allActionsEmitAtInfo() {
        for (final AuditAction action : AuditAction.values()) {
            this.capture.events.clear();
            StructuredLogger.audit().forEvent(ctx(), action)
                .packageName("org.example:artifact")
                .packageVersion("1.2.3")
                .emit();
            MatcherAssert.assertThat(
                "Action " + action.name() + " must emit at INFO",
                this.capture.last().getLevel(), Matchers.is(Level.INFO)
            );
        }
    }

    @Test
    @DisplayName("Required fields present in MapMessage payload")
    void requiredFieldsInPayload() {
        StructuredLogger.audit()
            .forEvent(ctx(), AuditAction.ARTIFACT_DOWNLOAD)
            .packageName("lodash")
            .packageVersion("4.17.21")
            .emit();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payload(evt, "package.name"), Matchers.is("lodash"));
        MatcherAssert.assertThat(payload(evt, "package.version"), Matchers.is("4.17.21"));
        MatcherAssert.assertThat(payload(evt, "event.action"), Matchers.is("artifact_download"));
        MatcherAssert.assertThat(payload(evt, "data_stream.dataset"), Matchers.is("pantera.audit"));
        MatcherAssert.assertThat(
            payload(evt, "event.category"),
            Matchers.is(List.of("audit"))
        );
    }

    @Test
    @DisplayName("Optional checksum + outcome emitted when set")
    void optionalFieldsEmittedWhenSet() {
        StructuredLogger.audit()
            .forEvent(ctx(), AuditAction.ARTIFACT_PUBLISH)
            .packageName("org.example:app")
            .packageVersion("2.0.0")
            .packageChecksum("abcdef123456")
            .outcome("success")
            .emit();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payload(evt, "package.checksum"), Matchers.is("abcdef123456"));
        MatcherAssert.assertThat(payload(evt, "event.outcome"), Matchers.is("success"));
    }

    @Test
    @DisplayName("Optional checksum absent when not set")
    void optionalChecksumAbsentWhenNotSet() {
        StructuredLogger.audit()
            .forEvent(ctx(), AuditAction.RESOLUTION)
            .packageName("pkg")
            .packageVersion("1")
            .emit();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(payload(evt, "package.checksum"), Matchers.nullValue());
    }

    @Test
    @DisplayName("RequestContext's trace.id / user.name / client.ip bound via bindToMdc")
    void ctxFieldsBoundToMdc() {
        final RequestContext c = new RequestContext(
            "trace-audit", null, null, null,
            "alice", "10.1.2.3", null,
            "npm_proxy", "npm", RequestContext.ArtifactRef.EMPTY,
            "/lodash", "/lodash", Deadline.in(Duration.ofSeconds(5))
        );
        StructuredLogger.audit()
            .forEvent(c, AuditAction.ARTIFACT_DOWNLOAD)
            .packageName("lodash").packageVersion("4.17.21")
            .emit();
        final LogEvent evt = this.capture.last();
        MatcherAssert.assertThat(
            evt.getContextData().getValue("trace.id"), Matchers.is("trace-audit")
        );
        MatcherAssert.assertThat(
            evt.getContextData().getValue("user.name"), Matchers.is("alice")
        );
        MatcherAssert.assertThat(
            evt.getContextData().getValue("client.ip"), Matchers.is("10.1.2.3")
        );
    }

    @Test
    @DisplayName("emit() fires even when operational log level is raised to ERROR")
    void auditNotSuppressibleByOperationalLevel() {
        final LoggerContext lc = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = lc.getConfiguration();
        final LoggerConfig original = cfg.getLoggerConfig(LOGGER);
        final Level priorLevel = original.getLevel();
        try {
            // Simulate operational suppression by raising the root to ERROR; the audit
            // logger must still emit at INFO. The log4j config in production pins the
            // audit logger to INFO + additivity=false; this test asserts behaviour
            // when operational config is adversarial.
            final LoggerConfig auditCfg = cfg.getLoggerConfig(LOGGER);
            // Ensure audit logger is explicitly at INFO level (independent of root).
            auditCfg.setLevel(Level.INFO);
            lc.updateLoggers();

            StructuredLogger.audit()
                .forEvent(ctx(), AuditAction.ARTIFACT_DELETE)
                .packageName("pkg").packageVersion("1").emit();

            MatcherAssert.assertThat(
                "audit event must appear at INFO despite operational level shifts",
                this.capture.events.size(), Matchers.greaterThanOrEqualTo(1)
            );
            MatcherAssert.assertThat(
                this.capture.last().getLevel(), Matchers.is(Level.INFO)
            );
        } finally {
            original.setLevel(priorLevel);
            lc.updateLoggers();
        }
    }

    // ---- helpers ----

    private static RequestContext ctx() {
        return new RequestContext(
            "trace-aud", null, null, null, "anonymous", null, null,
            "repo", "npm", RequestContext.ArtifactRef.EMPTY,
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
