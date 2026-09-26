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
package com.auto1.pantera.audit;

import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.EcsLoggingSlice;

/**
 * Correlation context threaded explicitly into every {@link AuditLogger} call.
 *
 * <p>Replaces the pre-2026-07 pattern of reading {@code trace.id} / {@code client.ip}
 * from MDC inside {@link AuditLogger} itself. That pattern silently produced empty
 * fields whenever the calling code ran on a thread that never had
 * {@code EcsLoggingSlice}'s MDC bound to it — Quartz worker threads, RxJava
 * continuations, and any adapter that forgot to call
 * {@link com.auto1.pantera.http.log.RequestContextHeaders#bindToMdc}. There was no
 * compiler-enforced way to catch a missing bind; the field was just quietly absent
 * in the shipped audit log.
 *
 * <p>{@link AuditLogger}'s methods require an {@code AuditContext} argument, so a
 * caller cannot emit an audit event without deciding where {@code traceId} and
 * {@code clientIp} come from. Both fields are optional (nullable) at the type level
 * because a handful of legitimate callers — CLI tools, background jobs with no
 * originating request — genuinely have neither; {@link AuditLogger} passes them to
 * {@code EcsLogger.field(...)}, which already no-ops on {@code null}.
 *
 * @param traceId  ECS {@code trace.id}, or {@code null} if none is available
 * @param clientIp ECS {@code client.ip}, or {@code null} if none is available
 * @since 2.2.0
 */
public record AuditContext(String traceId, String clientIp) {

    /**
     * Sentinel for callers with no request context at all (CLI tools, startup-time
     * jobs). Prefer threading the real context when one exists; use this only when
     * there genuinely is none.
     */
    public static final AuditContext NONE = new AuditContext(null, null);

    /**
     * Context of the request carrying these headers: the internal
     * {@code X-Pantera-Ctx-Trace-Id} / {@code X-Pantera-Ctx-Client-Ip}
     * headers {@link EcsLoggingSlice} stamps on every inbound request.
     *
     * <p>This is the way to build the context at slice entry. The headers
     * travel with the request, so they are authoritative on any thread; the
     * thread's MDC is never consulted, because a pooled worker, event-loop
     * or HTTP-client thread can still hold an earlier, unrelated request's
     * values. A header that is absent or blank yields {@code null}.
     *
     * @param headers Request headers
     */
    public AuditContext(final Headers headers) {
        this(
            AuditContext.header(headers, EcsLoggingSlice.CTX_TRACE_ID_HEADER),
            AuditContext.header(headers, EcsLoggingSlice.CTX_CLIENT_IP_HEADER)
        );
    }

    /**
     * This context as the internal {@code X-Pantera-Ctx-*} request headers, for
     * a slice that re-enters another slice on the same request's behalf: the
     * callee restores the request's trace id / client IP from these headers on
     * its worker threads instead of inheriting a pooled thread's stale MDC.
     *
     * @return Fresh headers carrying the non-empty fields of this context
     */
    public Headers requestHeaders() {
        final Headers out = new Headers();
        if (this.traceId != null && !this.traceId.isEmpty()) {
            out.add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, this.traceId);
        }
        if (this.clientIp != null && !this.clientIp.isEmpty()) {
            out.add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, this.clientIp);
        }
        return out;
    }

    /**
     * First non-blank value of a header.
     *
     * @param headers Headers
     * @param name Header name
     * @return Value, or {@code null} when absent or blank
     */
    private static String header(final Headers headers, final String name) {
        return headers.find(name).stream()
            .map(Header::getValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }
}
