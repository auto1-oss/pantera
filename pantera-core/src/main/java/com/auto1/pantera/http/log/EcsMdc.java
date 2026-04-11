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

/**
 * ECS MDC field key constants.
 *
 * <p>These constants define the fields that are set in SLF4J/Log4j2 MDC (ThreadContext)
 * for the lifetime of each HTTP request by {@link EcsLoggingSlice}. EcsLayout
 * automatically emits all MDC entries as top-level ECS JSON fields.
 *
 * <p><strong>Ownership rule (no-duplicate guarantee):</strong>
 * <ul>
 *   <li>Fields defined here are MDC-owned: set once per request by EcsLoggingSlice,
 *       propagated to all log events for that request, cleared at request end.</li>
 *   <li>These field keys must NEVER appear in {@link EcsLogEvent} or {@link EcsLogger}
 *       field maps — doing so creates duplicate keys in Elasticsearch and the document
 *       is rejected.</li>
 * </ul>
 *
 * <p>All MDC put/remove calls must use these constants to prevent key drift.
 *
 * @since 1.21.0
 */
public final class EcsMdc {

    /**
     * ECS {@code trace.id} — 16-character hex request correlation ID.
     * Set from X-Request-ID / X-Trace-ID header or generated as UUID substring.
     */
    public static final String TRACE_ID = "trace.id";

    /**
     * ECS {@code span.id} — 16-character hex span identifier for this request.
     * Always generated fresh per request by {@link com.auto1.pantera.http.log.SpanContext}.
     *
     * @since 2.1.0
     */
    public static final String SPAN_ID = "span.id";

    /**
     * ECS {@code span.parent.id} — 16-character hex span identifier of the caller.
     * Extracted from incoming B3/W3C span-id header; absent if no upstream span.
     *
     * @since 2.1.0
     */
    public static final String PARENT_SPAN_ID = "span.parent.id";

    /**
     * ECS {@code client.ip} — originating client IP address.
     * Extracted from X-Forwarded-For → X-Real-IP → TCP remote address, in that order.
     */
    public static final String CLIENT_IP = "client.ip";

    /**
     * ECS {@code user.name} — authenticated username.
     * Extracted from Basic auth header only; Bearer tokens resolved via JWT claim
     * by the auth middleware which updates this MDC entry.
     */
    public static final String USER_NAME = "user.name";

    private EcsMdc() {
        // constants only
    }
}
