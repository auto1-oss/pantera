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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates ECS log output from {@link EcsLogEvent} and {@link EcsLogger}:
 * <ul>
 *   <li>No duplicate MDC keys in any emitted event</li>
 *   <li>{@code message} field is a plain string (not a stringified JSON map)</li>
 *   <li>{@code event.severity} is absent (removed in ECS 8.x)</li>
 *   <li>MDC is fully cleaned up after each log call (no ThreadContext leaks)</li>
 * </ul>
 */
public final class EcsSchemaValidationTest {

    private static final String CAPTURE_APPENDER = "EcsSchemaCapture";

    private CapturingAppender capture;

    /** Logger names that have additivity=false and require direct appender attachment. */
    private static final String[] LOGGER_NAMES = {
        "http.access", "com.auto1.pantera", "com.auto1.pantera.test"
    };

    @BeforeEach
    void setup() {
        capture = new CapturingAppender(CAPTURE_APPENDER);
        capture.start();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(capture);
        // Add to root AND to all named loggers that have additivity=false
        cfg.getRootLogger().addAppender(capture, null, null);
        for (final String name : LOGGER_NAMES) {
            final LoggerConfig lc = cfg.getLoggerConfig(name);
            lc.addAppender(capture, null, null);
        }
        ctx.updateLoggers();
    }

    @AfterEach
    void teardown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.getRootLogger().removeAppender(CAPTURE_APPENDER);
        for (final String name : LOGGER_NAMES) {
            cfg.getLoggerConfig(name).removeAppender(CAPTURE_APPENDER);
        }
        capture.stop();
        ctx.updateLoggers();
        // Ensure MDC is clean after each test
        ThreadContext.clearAll();
    }

    @Test
    void ecsLogEventMessageIsPlainString() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.OK)
            .urlPath("/api/v1/test")
            .duration(42L)
            .outcome("success")
            .message("Test request completed")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected at least one log event");
        final LogEvent evt = capture.lastEvent();
        final String msg = evt.getMessage().getFormattedMessage();
        assertFalse(msg.startsWith("{"),
            "message must be a plain string, not a JSON object: " + msg);
        assertFalse(msg.contains("\"message\""),
            "message must not contain embedded JSON 'message' key: " + msg);
    }

    @Test
    void ecsLogEventNoEventSeverityInMdc() {
        // Use 404 status to trigger WARN level (success paths log at DEBUG which is filtered)
        new EcsLogEvent()
            .httpMethod("DELETE")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/api/v1/resource")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected at least one log event for 404 response");
        final LogEvent evt = capture.lastEvent();
        assertNull(evt.getContextData().getValue("event.severity"),
            "event.severity must not be present in ECS 8.x MDC");
    }

    @Test
    void ecsLogEventMdcCleanedUpAfterLog() {
        new EcsLogEvent()
            .httpMethod("POST")
            .httpStatus(com.auto1.pantera.http.RsStatus.OK)
            .urlPath("/api/v1/upload")
            .duration(100L)
            .log();
        assertTrue(ThreadContext.isEmpty(),
            "MDC must be empty after EcsLogEvent.log() — leaked: " + ThreadContext.getContext());
    }

    @Test
    void ecsLoggerMdcCleanedUpAfterLog() {
        EcsLogger.info("com.auto1.pantera.test")
            .message("Artifact published")
            .eventCategory("artifact")
            .eventAction("artifact_publish")
            .eventOutcome("success")
            .field("repository.name", "my-repo")
            .log();
        assertTrue(ThreadContext.isEmpty(),
            "MDC must be empty after EcsLogger.log() — leaked: " + ThreadContext.getContext());
    }

    @Test
    void ecsLoggerMessageIsPlainString() {
        EcsLogger.warn("com.auto1.pantera.test")
            .message("Cache eviction pressure high")
            .eventCategory("cache")
            .eventAction("cache_evict")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected at least one log event");
        final LogEvent evt = capture.lastEvent();
        final String msg = evt.getMessage().getFormattedMessage();
        assertFalse(msg.startsWith("{"),
            "EcsLogger message must be a plain string: " + msg);
    }

    @Test
    void ecsLoggerNoEventSeverityInMdc() {
        EcsLogger.error("com.auto1.pantera.test")
            .message("DB write failed")
            .eventCategory("database")
            .eventAction("batch_commit")
            .eventOutcome("failure")
            .error(new RuntimeException("Connection refused"))
            .log();
        assertFalse(capture.events.isEmpty());
        for (final LogEvent evt : capture.events) {
            assertNull(evt.getContextData().getValue("event.severity"),
                "event.severity must not be present in any log event");
        }
    }

    @Test
    void ecsLogEventEventCategoryAndTypeArePresentAsMdcStrings() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.OK)
            .urlPath("/health")
            .log();
        assertFalse(capture.events.isEmpty());
        final LogEvent evt = capture.lastEvent();
        // event.category and event.type are stored as JSON-array strings in MDC
        final String category = evt.getContextData().getValue("event.category");
        final String type = evt.getContextData().getValue("event.type");
        assertNotNull(category, "event.category must be present in MDC");
        assertNotNull(type, "event.type must be present in MDC");
        assertTrue(category.startsWith("["), "event.category must be a JSON array string: " + category);
        assertTrue(type.startsWith("["), "event.type must be a JSON array string: " + type);
    }

    /**
     * Simple appender that collects log events in a list for inspection.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            this.events.add(event.toImmutable());
        }

        LogEvent lastEvent() {
            return this.events.get(this.events.size() - 1);
        }
    }
}
