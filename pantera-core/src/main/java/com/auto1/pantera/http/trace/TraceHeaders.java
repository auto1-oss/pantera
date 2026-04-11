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
import com.auto1.pantera.http.log.EcsMdc;
import org.slf4j.MDC;

/**
 * Utility for injecting B3 + W3C trace context headers into outgoing HTTP requests.
 *
 * <p>Reads the current request's trace.id and span.id from MDC (set by
 * {@link SpanContext} via EcsLoggingSlice) and generates a child span for
 * the outgoing call. If no trace context is in MDC, no headers are added.
 *
 * <p>Emitted headers:
 * <ul>
 *   <li>{@code X-B3-TraceId} — same trace.id as current request</li>
 *   <li>{@code X-B3-SpanId} — freshly generated child span</li>
 *   <li>{@code X-B3-ParentSpanId} — current request's span.id</li>
 *   <li>{@code traceparent} — W3C Trace Context format</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class TraceHeaders {

    private TraceHeaders() {
        // utility class
    }

    /**
     * Add B3 + W3C trace headers to an existing Headers set for outgoing calls.
     *
     * <p>Returns a <strong>copy</strong> of the input headers with trace headers
     * appended. The original Headers instance is not modified.
     *
     * @param existing Headers to augment
     * @return New Headers instance with trace context headers added,
     *         or the original instance unchanged if no trace context is in MDC
     */
    public static Headers inject(final Headers existing) {
        final String traceId = MDC.get(EcsMdc.TRACE_ID);
        final String currentSpan = MDC.get(EcsMdc.SPAN_ID);
        if (traceId == null || currentSpan == null) {
            return existing;
        }
        final String childSpan = SpanContext.generateHex16();
        return existing.copy()
            .add(new Header("X-B3-TraceId", traceId))
            .add(new Header("X-B3-SpanId", childSpan))
            .add(new Header("X-B3-ParentSpanId", currentSpan))
            .add(new Header("traceparent",
                String.format("00-%s-%s-01", traceId, childSpan)));
    }

    /**
     * Build trace headers as flat key-value pairs for {@code java.net.http.HttpRequest}.
     *
     * <p>Intended for use with {@code HttpRequest.Builder.headers(String...)}:
     * <pre>{@code
     *     HttpRequest.newBuilder()
     *         .uri(uri)
     *         .headers(TraceHeaders.httpClientHeaders())
     *         .build();
     * }</pre>
     *
     * @return Alternating key-value array (name1, value1, name2, value2, ...),
     *         or empty array if no trace context is in MDC
     */
    public static String[] httpClientHeaders() {
        final String traceId = MDC.get(EcsMdc.TRACE_ID);
        final String currentSpan = MDC.get(EcsMdc.SPAN_ID);
        if (traceId == null || currentSpan == null) {
            return new String[0];
        }
        final String childSpan = SpanContext.generateHex16();
        return new String[]{
            "X-B3-TraceId", traceId,
            "X-B3-SpanId", childSpan,
            "X-B3-ParentSpanId", currentSpan,
            "traceparent", String.format("00-%s-%s-01", traceId, childSpan)
        };
    }
}
