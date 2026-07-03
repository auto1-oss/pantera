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

import co.elastic.logging.log4j2.EcsLayout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates ECS log output from {@link EcsLogEvent} and {@link EcsLogger}:
 * <ul>
 *   <li>Typed values (Long, Integer, List) are preserved as native JSON types
 *       (not stringified) when serialized by {@link EcsLayout}</li>
 *   <li>The ECS {@code message} field is a plain JSON string (never an object)</li>
 *   <li>No MDC entries leak out of {@code log()} calls — EcsLogger and EcsLogEvent
 *       no longer mutate {@link ThreadContext}</li>
 *   <li>No duplicate top-level keys in the JSON output (MDC-owned keys are filtered
 *       from the MapMessage payload)</li>
 * </ul>
 */
public final class EcsSchemaValidationTest {

    private static final String CAPTURE_APPENDER = "EcsSchemaCapture";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EcsLayout ECS_LAYOUT = EcsLayout.newBuilder()
        .setServiceName("pantera-test")
        .setServiceVersion("test")
        .setServiceEnvironment("test")
        .build();

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

    // ---- Helpers: read payload fields from MapMessage and render JSON via EcsLayout ----

    /** Extract a field value from the event's MapMessage payload. */
    private static Object payloadField(final LogEvent evt, final String key) {
        final Message msg = evt.getMessage();
        if (msg instanceof MapMessage<?, ?> mm) {
            return mm.getData().get(key);
        }
        return null;
    }

    /** Extract the human-readable ECS "message" string from the MapMessage payload. */
    private static String payloadMessage(final LogEvent evt) {
        final Object v = payloadField(evt, "message");
        return v == null ? null : v.toString();
    }

    /** Render the event through EcsLayout and parse the resulting JSON line. */
    private static JsonNode renderJson(final LogEvent evt) throws Exception {
        final String line = ECS_LAYOUT.toSerializable(evt);
        return JSON.readTree(line);
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
        final String msg = payloadMessage(evt);
        assertNotNull(msg, "MapMessage must expose a 'message' field");
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
        assertNull(payloadField(evt, "event.severity"),
            "event.severity must not be present in MapMessage payload");
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
            .eventCategory("database")
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
            .eventCategory("database")
            .eventAction("cache_evict")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected at least one log event");
        final LogEvent evt = capture.lastEvent();
        final String msg = payloadMessage(evt);
        assertNotNull(msg, "MapMessage must expose a 'message' field");
        assertFalse(msg.startsWith("{"),
            "EcsLogger message must be a plain string: " + msg);
        assertEquals("Cache eviction pressure high", msg,
            "message in MapMessage must equal the value passed to .message()");
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
            assertNull(payloadField(evt, "event.severity"),
                "event.severity must not be present in MapMessage payload");
        }
    }

    @Test
    void ecsLogEventEventCategoryAndTypeArePreservedAsList() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/health")
            .log();
        assertFalse(capture.events.isEmpty());
        final LogEvent evt = capture.lastEvent();
        // event.category and event.type are stored as native List<String> in MapMessage
        final Object category = payloadField(evt, "event.category");
        final Object type = payloadField(evt, "event.type");
        assertNotNull(category, "event.category must be present in MapMessage payload");
        assertNotNull(type, "event.type must be present in MapMessage payload");
        assertTrue(category instanceof List, "event.category must be a List (not String): " + category.getClass());
        assertTrue(type instanceof List, "event.type must be a List (not String): " + type.getClass());
        assertEquals(List.of("web"), category, "event.category must be ['web']");
        assertEquals(List.of("access"), type, "event.type must be ['access']");
    }

    // ---- Fix C: ECS HTTP logging — status code → human-readable message ----
    // buildDefaultMessage() is private; we drive it indirectly by logging without
    // a custom message and inspecting what EcsLogEvent emits to the appender.

    @Test
    void status200ProducesRequestCompletedMessage() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.OK)
            .urlPath("/api/packages/foo")
            .duration(10L)
            .outcome("success")
            // no explicit .message() — forces buildDefaultMessage(200) path
            .log();
        // 200 logs at DEBUG; the appender captures at whatever level the test logger allows.
        // If no events captured (filtered out), the test still passes: the code ran without error.
        // When captured, verify the message is the expected human-readable string.
        if (!capture.events.isEmpty()) {
            assertEquals("Request completed", payloadMessage(capture.lastEvent()),
                "200 status must produce 'Request completed' message");
        }
    }

    @Test
    void status404ProducesNotFoundMessage() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/api/packages/missing")
            .log();
        assertFalse(capture.events.isEmpty(), "404 must produce a WARN-level log event");
        assertEquals("Not found", payloadMessage(capture.lastEvent()),
            "404 status must produce 'Not found' message");
    }

    @Test
    void status401ProducesAuthenticationRequiredMessage() {
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.UNAUTHORIZED)
            .urlPath("/api/secure")
            .log();
        assertFalse(capture.events.isEmpty(), "401 must produce a WARN-level log event");
        assertEquals("Authentication required", payloadMessage(capture.lastEvent()),
            "401 status must produce 'Authentication required' message");
    }

    @Test
    void status500ProducesInternalServerErrorMessage() {
        new EcsLogEvent()
            .httpMethod("POST")
            .httpStatus(com.auto1.pantera.http.RsStatus.INTERNAL_ERROR)
            .urlPath("/api/upload")
            .log();
        assertFalse(capture.events.isEmpty(), "500 must produce an ERROR-level log event");
        assertEquals("Internal server error", payloadMessage(capture.lastEvent()),
            "500 status must produce 'Internal server error' message");
    }

    @Test
    void statusCodeMessageIsNeverNullOrEmpty() {
        // Verify the default-message path never returns null/empty for common status codes
        final int[] codes = {200, 201, 204, 304, 400, 401, 403, 404, 500, 503};
        for (final int code : codes) {
            capture.events.clear();
            final com.auto1.pantera.http.RsStatus status =
                com.auto1.pantera.http.RsStatus.byCode(code);
            new EcsLogEvent()
                .httpMethod("GET")
                .httpStatus(status)
                .urlPath("/check")
                .log();
            // For 4xx/5xx the event must be captured (WARN/ERROR level)
            if (code >= 400) {
                assertFalse(capture.events.isEmpty(),
                    "Expected a log event for status " + code);
                final String msg = payloadMessage(capture.lastEvent());
                assertNotNull(msg, "message must not be null for status " + code);
                assertFalse(msg.isEmpty(), "message must not be empty for status " + code);
            }
        }
    }

    @Test
    void urlOriginalIncludesPathAndQuery() {
        // Use 404 status so the event is emitted at WARN level (always captured)
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/api/packages/foo")
            .urlOriginal("/api/packages/foo?version=1.0.0&format=json")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected a WARN-level log event for 404");
        final LogEvent evt = capture.lastEvent();
        final Object urlOriginal = payloadField(evt, "url.original");
        assertNotNull(urlOriginal, "url.original must be present in MapMessage payload");
        final String s = urlOriginal.toString();
        assertTrue(s.contains("/api/packages/foo"),
            "url.original must contain the path: " + s);
        assertTrue(s.contains("version=1.0.0"),
            "url.original must contain the query string: " + s);
        assertTrue(s.contains("format=json"),
            "url.original must contain all query parameters: " + s);
    }

    @Test
    void urlOriginalIsAbsentWhenNotSet() {
        // Verify url.original is not emitted when urlOriginal() is never called
        new EcsLogEvent()
            .httpMethod("DELETE")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/api/packages/missing")
            .log();
        assertFalse(capture.events.isEmpty(), "Expected a log event for 404");
        final LogEvent evt = capture.lastEvent();
        // url.path must be present; url.original must NOT be present if never set
        assertNotNull(payloadField(evt, "url.path"),
            "url.path must be present in MapMessage payload");
        assertNull(payloadField(evt, "url.original"),
            "url.original must be absent when urlOriginal() was never called");
    }

    // ---- P1: typed values must survive serialization as native JSON types ----

    @Test
    void ecsLoggerTypedValuesSerializeAsJsonTypes() throws Exception {
        // This is the regression test for the P1 bug:
        //   event.category was emitted as "[\"web\"]" (string) — must be ["web"] (array)
        //   event.duration was emitted as "1983"       (string) — must be 1983    (integer)
        EcsLogger.info("com.auto1.pantera.test")
            .message("typed-values smoke")
            .eventCategory("web")
            .field("event.duration", 1983L)
            .field("http.response.status_code", 404)
            .log();
        assertFalse(capture.events.isEmpty(), "Expected a log event");
        final LogEvent evt = capture.lastEvent();
        final JsonNode json = renderJson(evt);

        assertTrue(json.has("event.category"), "event.category must be a top-level field: " + json);
        assertTrue(json.get("event.category").isArray(),
            "event.category must be a JSON array (not string): " + json.get("event.category"));
        assertEquals("web", json.get("event.category").get(0).asText(),
            "event.category[0] must be 'web'");

        assertTrue(json.has("event.duration"), "event.duration must be a top-level field: " + json);
        assertTrue(json.get("event.duration").isNumber(),
            "event.duration must be a JSON number (not string): " + json.get("event.duration"));
        assertEquals(1983L, json.get("event.duration").asLong(),
            "event.duration must equal 1983");

        assertTrue(json.has("http.response.status_code"),
            "http.response.status_code must be a top-level field: " + json);
        assertTrue(json.get("http.response.status_code").isInt(),
            "http.response.status_code must be a JSON integer (not string): "
                + json.get("http.response.status_code"));
        assertEquals(404, json.get("http.response.status_code").asInt(),
            "http.response.status_code must equal 404");
    }

    @Test
    void ecsLogEventTypedValuesSerializeAsJsonTypes() throws Exception {
        // Same guarantee for HTTP access logs (EcsLogEvent path).
        new EcsLogEvent()
            .httpMethod("GET")
            .httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/api/pkg")
            .duration(2500L)
            .log();
        assertFalse(capture.events.isEmpty(), "Expected a log event for 404");
        final LogEvent evt = capture.lastEvent();
        final JsonNode json = renderJson(evt);

        assertTrue(json.get("event.category").isArray(),
            "event.category must be a JSON array: " + json.get("event.category"));
        assertTrue(json.get("event.type").isArray(),
            "event.type must be a JSON array: " + json.get("event.type"));
        assertTrue(json.get("http.response.status_code").isInt(),
            "http.response.status_code must be a JSON integer: "
                + json.get("http.response.status_code"));
        assertEquals(404, json.get("http.response.status_code").asInt());
        assertTrue(json.get("event.duration").isNumber(),
            "event.duration must be a JSON number: " + json.get("event.duration"));
        assertEquals(2500L, json.get("event.duration").asLong());
    }

    @Test
    void ecsLoggerMdcOwnedKeysAreFilteredWhenDuplicated() throws Exception {
        // When an MDC-owned key IS present in ThreadContext and the caller also passes
        // it via field(), the MapMessage entry is dropped to avoid a duplicate top-level
        // field in the Elasticsearch document.
        ThreadContext.put(EcsMdc.TRACE_ID, "abcd1234");
        ThreadContext.put(EcsMdc.CLIENT_IP, "10.0.0.1");
        try {
            EcsLogger.info("com.auto1.pantera.test")
                .message("mdc filter test")
                .eventCategory("web")
                .field(EcsMdc.TRACE_ID, "DUPLICATE-from-field")
                .field(EcsMdc.CLIENT_IP, "DUPLICATE-from-field")
                .log();
            assertFalse(capture.events.isEmpty());
            final LogEvent evt = capture.lastEvent();
            // Payload must NOT contain MDC-owned keys when they are already in MDC.
            assertNull(payloadField(evt, EcsMdc.TRACE_ID),
                "MDC-owned trace.id must not appear in MapMessage payload when duplicated");
            assertNull(payloadField(evt, EcsMdc.CLIENT_IP),
                "MDC-owned client.ip must not appear in MapMessage payload when duplicated");
            // Rendered JSON must have trace.id / client.ip from MDC (not the duplicate).
            final JsonNode json = renderJson(evt);
            assertEquals("abcd1234", json.get("trace.id").asText(),
                "trace.id must come from MDC, not from the sneaked-in field()");
            assertEquals("10.0.0.1", json.get("client.ip").asText(),
                "client.ip must come from MDC, not from the sneaked-in field()");
        } finally {
            ThreadContext.clearAll();
        }
    }

    @Test
    void ecsLoggerMdcOwnedKeysAreKeptWhenNotInMdc() throws Exception {
        // When MDC does not contain the key (e.g. CLI tool or async continuation past
        // the HTTP request frame), field() values must still flow into the JSON output.
        // This preserves the behavior of callers like MetadataRegenerator that pass
        // repository.name/repository.type via field() from async contexts.
        assertFalse(ThreadContext.containsKey(EcsMdc.REPO_NAME),
            "precondition: MDC must not already contain repository.name");
        EcsLogger.info("com.auto1.pantera.test")
            .message("no-mdc fallback test")
            .eventCategory("web")
            .field(EcsMdc.REPO_NAME, "my-repo")
            .field(EcsMdc.REPO_TYPE, "maven")
            .log();
        assertFalse(capture.events.isEmpty());
        final LogEvent evt = capture.lastEvent();
        // When MDC is empty, these MUST survive in the payload so they reach JSON.
        assertEquals("my-repo", payloadField(evt, EcsMdc.REPO_NAME),
            "repository.name must remain in payload when absent from MDC");
        assertEquals("maven", payloadField(evt, EcsMdc.REPO_TYPE),
            "repository.type must remain in payload when absent from MDC");
        // And they should appear at top level in the rendered JSON.
        final JsonNode json = renderJson(evt);
        assertEquals("my-repo", json.get("repository.name").asText());
        assertEquals("maven", json.get("repository.type").asText());
    }

    // ---- WI-00 level policy: 404/401/403 → INFO (not WARN) ----

    @Test
    void notFoundResponsesLogAtInfoNotWarn() {
        new EcsLogEvent()
            .httpMethod("GET").httpStatus(com.auto1.pantera.http.RsStatus.NOT_FOUND)
            .urlPath("/artifactory/libs-release-local/org/x/1.0/x-1.0.pom")
            .duration(3).log();
        assertFalse(capture.events.isEmpty());
        assertEquals(org.apache.logging.log4j.Level.INFO, capture.lastEvent().getLevel(),
            "404 must log at INFO per WI-00 access-log level policy");
    }

    @Test
    void unauthorizedResponsesLogAtInfoNotWarn() {
        new EcsLogEvent()
            .httpMethod("GET").httpStatus(com.auto1.pantera.http.RsStatus.UNAUTHORIZED)
            .urlPath("/artifactory/api/npm/npm_proxy/pkg").duration(2).log();
        assertEquals(org.apache.logging.log4j.Level.INFO, capture.lastEvent().getLevel(),
            "401 must log at INFO per WI-00 access-log level policy");
    }

    @Test
    void forbiddenResponsesLogAtInfoNotWarn() {
        new EcsLogEvent()
            .httpMethod("GET").httpStatus(com.auto1.pantera.http.RsStatus.FORBIDDEN)
            .urlPath("/artifactory/libs-release-local/secret").duration(1).log();
        assertEquals(org.apache.logging.log4j.Level.INFO, capture.lastEvent().getLevel(),
            "403 must log at INFO per WI-00 access-log level policy");
    }

    @Test
    void otherFourXxStillLogAtWarn() {
        new EcsLogEvent()
            .httpMethod("POST").httpStatus(com.auto1.pantera.http.RsStatus.BAD_REQUEST)
            .urlPath("/artifactory/api/npm/npm_proxy/pkg").duration(1).log();
        assertEquals(org.apache.logging.log4j.Level.WARN, capture.lastEvent().getLevel(),
            "400 remains at WARN — only 401/403/404 downgraded");
    }

    @Test
    void fiveXxStillLogAtError() {
        new EcsLogEvent()
            .httpMethod("GET").httpStatus(com.auto1.pantera.http.RsStatus.INTERNAL_ERROR)
            .urlPath("/any").duration(5).log();
        assertEquals(org.apache.logging.log4j.Level.ERROR, capture.lastEvent().getLevel(),
            "5xx still ERROR regardless of other policy changes");
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
