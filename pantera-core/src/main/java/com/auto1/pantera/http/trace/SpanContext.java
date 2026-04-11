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
package com.auto1.pantera.http.trace;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Extracts or generates trace.id, span.id, and span.parent.id from HTTP
 * request headers per B3 (openzipkin) and W3C Trace Context standards.
 *
 * <p><strong>Extraction precedence:</strong>
 * <ol>
 *   <li>{@code b3} single header (zipkin compact format)</li>
 *   <li>{@code X-B3-TraceId} + {@code X-B3-SpanId} multi headers</li>
 *   <li>{@code traceparent} (W3C Trace Context)</li>
 *   <li>{@code X-Trace-Id}</li>
 *   <li>{@code X-Request-Id}</li>
 *   <li>Generate random 16-hex-char ID</li>
 * </ol>
 *
 * <p><strong>Span lifecycle:</strong> incoming span.id becomes
 * {@code span.parent.id}; a new {@code span.id} is always generated for the
 * current request.
 *
 * <p><strong>SRE convention:</strong> all IDs are 16 lowercase hex characters
 * ({@code /[\da-f]{16}/}). W3C 32-char trace-ids are truncated to the last 16.
 * Malformed values are regenerated and logged at WARN with code SRE2042.
 *
 * @since 2.1.0
 */
public final class SpanContext {

    private static final String LOGGER_NAME = "com.auto1.pantera.http.log.SpanContext";

    /**
     * Exactly 16 lowercase hex characters.
     */
    private static final Pattern HEX16 = Pattern.compile("[\\da-f]{16}");

    /**
     * 32 lowercase hex characters (W3C trace-id).
     */
    private static final Pattern HEX32 = Pattern.compile("[\\da-f]{32}");

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    private SpanContext(
        final String traceId,
        final String spanId,
        final String parentSpanId
    ) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
    }

    /**
     * Extract trace context from request headers.
     *
     * @param headers HTTP request headers
     * @return SpanContext with validated or generated IDs
     */
    public static SpanContext extract(final Headers headers) {
        final String userAgent = firstValue(headers, "User-Agent");

        // Try extraction in precedence order
        SpanContext ctx = tryB3Single(headers, userAgent);
        if (ctx != null) {
            return ctx;
        }
        ctx = tryB3Multi(headers, userAgent);
        if (ctx != null) {
            return ctx;
        }
        ctx = tryTraceparent(headers, userAgent);
        if (ctx != null) {
            return ctx;
        }
        ctx = tryFallback(headers, userAgent);
        if (ctx != null) {
            return ctx;
        }

        // No trace headers at all — generate everything
        return new SpanContext(generateHex16(), generateHex16(), null);
    }

    /**
     * @return 16-hex-char trace ID
     */
    public String traceId() {
        return this.traceId;
    }

    /**
     * @return 16-hex-char span ID (always freshly generated for this request)
     */
    public String spanId() {
        return this.spanId;
    }

    /**
     * @return 16-hex-char parent span ID, or null if no upstream span
     */
    public String parentSpanId() {
        return this.parentSpanId;
    }

    // ------------------------------------------------------------------
    //  B3 single header: {traceId}-{spanId}[-{sampled}[-{parentSpanId}]]
    // ------------------------------------------------------------------
    private static SpanContext tryB3Single(final Headers headers, final String userAgent) {
        final String value = firstValue(headers, "b3");
        if (value == null) {
            return null;
        }
        final String[] parts = value.split("-");
        if (parts.length < 2) {
            return null;
        }
        final String rawTraceId = parts[0];
        final String rawSpanId = parts[1];
        final String traceId = validateOrRegenerate(rawTraceId, "trace.id", userAgent);
        final String parentSpanId = validateOrRegenerate(rawSpanId, "span.id", userAgent);
        return new SpanContext(traceId, generateHex16(), parentSpanId);
    }

    // ------------------------------------------------------------------
    //  B3 multi headers: X-B3-TraceId, X-B3-SpanId
    // ------------------------------------------------------------------
    private static SpanContext tryB3Multi(final Headers headers, final String userAgent) {
        final String rawTraceId = firstValue(headers, "X-B3-TraceId");
        final String rawSpanId = firstValue(headers, "X-B3-SpanId");
        if (rawTraceId == null || rawSpanId == null) {
            return null;
        }
        final String traceId = validateOrRegenerate(rawTraceId, "trace.id", userAgent);
        final String parentSpanId = validateOrRegenerate(rawSpanId, "span.id", userAgent);
        return new SpanContext(traceId, generateHex16(), parentSpanId);
    }

    // ------------------------------------------------------------------
    //  W3C traceparent: {version}-{trace-id}-{parent-id}-{trace-flags}
    //  trace-id is 32 hex; we take the last 16
    // ------------------------------------------------------------------
    private static SpanContext tryTraceparent(final Headers headers, final String userAgent) {
        final String value = firstValue(headers, "traceparent");
        if (value == null) {
            return null;
        }
        final String[] parts = value.split("-");
        if (parts.length < 4) {
            return null;
        }
        final String rawTraceId = parts[1];  // 32-hex
        final String rawSpanId = parts[2];   // 16-hex
        // Truncate 32-hex to last 16
        final String truncatedTraceId;
        if (HEX32.matcher(rawTraceId).matches()) {
            truncatedTraceId = rawTraceId.substring(16);
        } else {
            truncatedTraceId = rawTraceId;
        }
        final String traceId = validateOrRegenerate(truncatedTraceId, "trace.id", userAgent);
        final String parentSpanId = validateOrRegenerate(rawSpanId, "span.id", userAgent);
        return new SpanContext(traceId, generateHex16(), parentSpanId);
    }

    // ------------------------------------------------------------------
    //  Fallback: X-Trace-Id, X-Request-Id (trace only, no span)
    // ------------------------------------------------------------------
    private static SpanContext tryFallback(final Headers headers, final String userAgent) {
        String raw = firstValue(headers, "X-Trace-Id");
        if (raw == null) {
            raw = firstValue(headers, "X-Request-Id");
        }
        if (raw == null) {
            return null;
        }
        final String traceId = validateOrRegenerate(raw, "trace.id", userAgent);
        return new SpanContext(traceId, generateHex16(), null);
    }

    // ------------------------------------------------------------------
    //  Validation + generation helpers
    // ------------------------------------------------------------------

    /**
     * Validate a candidate ID against the 16-hex or 32-hex pattern.
     * If 32-hex, take the last 16. If invalid, regenerate and log SRE2042.
     */
    private static String validateOrRegenerate(
        final String raw,
        final String fieldName,
        final String userAgent
    ) {
        if (raw == null || raw.isEmpty()) {
            logMalformed(fieldName, raw, userAgent);
            return generateHex16();
        }
        final String lower = raw.toLowerCase();
        if (HEX16.matcher(lower).matches()) {
            return lower;
        }
        if (HEX32.matcher(lower).matches()) {
            return lower.substring(16);
        }
        logMalformed(fieldName, raw, userAgent);
        return generateHex16();
    }

    private static void logMalformed(
        final String fieldName,
        final String value,
        final String userAgent
    ) {
        final String safeValue = value == null ? "<null>" : value;
        final String safeUa = userAgent == null ? "<unknown>" : userAgent;
        EcsLogger.warn(LOGGER_NAME)
            .message(
                String.format(
                    "SRE2042 Malformed|Missing %s [%s] for user-agent [%s]",
                    fieldName, safeValue, safeUa
                )
            )
            .field("event.action", "trace_id_regenerated")
            .field("event.category", "configuration")
            .log();
    }

    /**
     * Generate a random 16-character lowercase hex string.
     */
    public static String generateHex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Get the first header value for a given name, or null.
     */
    private static String firstValue(final Headers headers, final String name) {
        final List<Header> found = headers.find(name);
        if (found.isEmpty()) {
            return null;
        }
        final String value = found.getFirst().getValue();
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
