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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import org.slf4j.MDC;

/**
 * Small helper that bridges Pantera's request-context internal headers
 * (set by {@code EcsLoggingSlice} on entry — see
 * {@link EcsLoggingSlice#CTX_TRACE_ID_HEADER} /
 * {@link EcsLoggingSlice#CTX_CLIENT_IP_HEADER}) back into the current
 * thread's MDC.
 *
 * <p>The motivation: Pantera's slice chain hops Vert.x worker threads
 * many times per request, and MDC is per-thread. The request-thread MDC
 * that {@code EcsLoggingSlice} populates does NOT survive those hops;
 * downstream slices that construct {@code ArtifactEvent}s or emit
 * audit-log records used to see {@code MDC.get("trace.id") == null} on
 * worker threads, so the audit record lost {@code trace.id} and
 * {@code client.ip} entirely.
 *
 * <p>Headers, by contrast, ARE explicit arguments on every
 * {@code Slice.response(line, headers, body)} call — so we propagate the
 * two correlation fields as internal headers, and slices that need them
 * call {@link #bindToMdc(Headers)} on their working thread right before
 * the code that reads MDC.</p>
 *
 * @since 2.2.0
 */
public final class RequestContextHeaders {

    private RequestContextHeaders() {
    }

    /**
     * Read the request-context headers from {@code headers} and put them
     * into the current thread's MDC under
     * {@link EcsMdc#TRACE_ID} / {@link EcsMdc#CLIENT_IP}. Empty or
     * already-present MDC values are not overwritten (i.e. an
     * inner-request-thread MDC takes precedence over the header — useful
     * when EcsLoggingSlice already set MDC for the calling thread).
     *
     * @param headers Inbound request headers carrying the internal
     *                {@code X-Pantera-Ctx-*} fields (may be empty if the
     *                request originated outside Pantera's standard
     *                pipeline; in that case this method is a no-op for
     *                missing fields).
     */
    public static void bindToMdc(final Headers headers) {
        if (MDC.get(EcsMdc.TRACE_ID) == null) {
            final String traceId = first(headers, EcsLoggingSlice.CTX_TRACE_ID_HEADER);
            if (traceId != null && !traceId.isEmpty()) {
                MDC.put(EcsMdc.TRACE_ID, traceId);
            }
        }
        if (MDC.get(EcsMdc.CLIENT_IP) == null) {
            final String clientIp = first(headers, EcsLoggingSlice.CTX_CLIENT_IP_HEADER);
            if (clientIp != null && !clientIp.isEmpty()) {
                MDC.put(EcsMdc.CLIENT_IP, clientIp);
            }
        }
    }

    private static String first(final Headers headers, final String name) {
        return headers.find(name).stream().findFirst().map(h -> h.getValue()).orElse(null);
    }
}
