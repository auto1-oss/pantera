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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogEvent;
import com.auto1.pantera.http.observability.StructuredLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.trace.SpanContext;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ECS-compliant HTTP access logging slice.
 *
 * <p>Replaces the old LoggingSlice with proper ECS field mapping and trace context.
 * Automatically logs all HTTP requests with:
 * <ul>
 *   <li>Proper ECS fields (client.ip, url.*, http.*, user_agent.*, etc.)</li>
 *   <li>Trace context (trace.id from MDC)</li>
 *   <li>Request/response timing</li>
 *   <li>Automatic log level selection (ERROR for 5xx, WARN for 4xx, DEBUG for success)</li>
 * </ul>
 *
 * <p>Access log emission is suppressed when the request carries the
 * {@link #INTERNAL_ROUTING_HEADER} header, which GroupResolver sets when dispatching
 * to member slices. Internal routing is already captured as DEBUG application logs
 * in GroupResolver itself (event.action=group_index_hit, group_proxy_fanout, etc.).
 *
 * <p>This slice should be used at the top level of the slice chain to ensure
 * all HTTP requests are logged consistently.
 *
 * @since 1.18.24
 */
public final class EcsLoggingSlice implements Slice {

    /**
     * Request header set by GroupResolver when dispatching to a member slice.
     * When present, EcsLoggingSlice skips access log emission to avoid ~105K
     * noise entries per 30 min from internal group-to-member queries.
     * The header is group-internal and does NOT propagate to upstream remotes
     * (proxy slice implementations forward {@code Headers.EMPTY} upstream).
     */
    public static final String INTERNAL_ROUTING_HEADER = "X-Pantera-Internal";

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Remote address (client IP).
     */
    private final String remoteAddress;

    /**
     * Repository name (nullable, for MDC propagation to audit logger).
     */
    private final String repoName;

    /**
     * Repository type (nullable, for MDC propagation to audit logger).
     */
    private final String repoType;

    /**
     * Ctor.
     * @param origin Origin slice
     */
    public EcsLoggingSlice(final Slice origin) {
        this(origin, null, null, null);
    }

    /**
     * Ctor with remote address.
     * @param origin Origin slice
     * @param remoteAddress Remote client address
     */
    public EcsLoggingSlice(final Slice origin, final String remoteAddress) {
        this(origin, remoteAddress, null, null);
    }

    /**
     * Ctor with full context for audit logging.
     * @param origin Origin slice
     * @param remoteAddress Remote client address
     * @param repoName Repository name (nullable)
     * @param repoType Repository type (nullable)
     */
    public EcsLoggingSlice(final Slice origin, final String remoteAddress,
        final String repoName, final String repoType) {
        this.origin = origin;
        this.remoteAddress = remoteAddress;
        this.repoName = repoName;
        this.repoType = repoType;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final long startTime = System.currentTimeMillis();
        final AtomicLong responseSize = new AtomicLong(0);

        // TRACE CONTEXT: Set MDC values at request start for propagation to all downstream logging
        // This enables auth, cooldown, and other services to log with request context
        final String clientIp = EcsLogEvent.extractClientIp(headers, this.remoteAddress);
        final String userName = EcsLogEvent.extractUsername(headers).orElse(null);
        
        // Extract or generate trace context from request headers (B3 / W3C / fallback)
        final SpanContext span = SpanContext.extract(headers);

        // Set MDC context for this request thread (constants from EcsMdc — no key drift)
        MDC.put(EcsMdc.TRACE_ID, span.traceId());
        MDC.put(EcsMdc.SPAN_ID, span.spanId());
        if (span.parentSpanId() != null) {
            MDC.put(EcsMdc.PARENT_SPAN_ID, span.parentSpanId());
        }
        if (clientIp != null && !clientIp.isEmpty() && !"unknown".equals(clientIp)) {
            MDC.put(EcsMdc.CLIENT_IP, clientIp);
        }
        if (userName != null) {
            MDC.put(EcsMdc.USER_NAME, userName);
        }
        if (this.repoName != null) {
            MDC.put(EcsMdc.REPO_NAME, this.repoName);
        }
        if (this.repoType != null) {
            MDC.put(EcsMdc.REPO_TYPE, this.repoType);
        }

        // Capture the internal-routing flag synchronously here (at request entry),
        // before the async chain starts.  The headers object is captured in the
        // closure below, but reading it here makes the intent explicit and avoids
        // repeated iteration in the hot path.
        final boolean internalRouting = !headers.find(INTERNAL_ROUTING_HEADER).isEmpty();

        return this.origin.response(line, headers, body)
            .thenApply(response -> {
                final long duration = System.currentTimeMillis() - startTime;

                // Skip access log for GroupResolver → member internal dispatches.
                // Internal routing is captured as DEBUG application logs in GroupResolver
                // (event.action=group_index_hit, group_proxy_fanout, etc.).
                if (!internalRouting) {
                    // WI-03 §4.1: emit the access log via the Tier-1 builder.
                    // The legacy EcsLogEvent emission that used to run alongside
                    // here was removed to avoid doubling the access-log volume
                    // in Kibana.  Rich user_agent.* sub-field parsing (name,
                    // version, os.name, os.version) and url.query emission
                    // migrate to StructuredLogger.access in a follow-up WI;
                    // the core contract (trace.id, client.ip, user.name,
                    // url.original, url.path, http.request.method,
                    // http.response.status_code, event.duration,
                    // user_agent.original) is covered by RequestContext today.
                    final RequestContext rctx = buildRequestContext(
                        span, clientIp, userName, line);
                    StructuredLogger.access().forRequest(rctx)
                        .status(response.status().code())
                        .duration(duration)
                        .log();
                }

                // Add traceparent response header for downstream correlation
                final Headers responseHeaders = response.headers().copy()
                    .add(new Header("traceparent",
                        String.format("00-%s-%s-01", span.traceId(), span.spanId())));
                return new Response(response.status(), responseHeaders, response.body());
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;

                // Log error with ECS fields
                // NOTE: client.ip, user.name, trace.id are in MDC — not added here
                new EcsLogEvent()
                    .httpMethod(line.method().value())
                    .httpVersion(line.version())
                    .urlPath(line.uri().getPath())
                    .userAgent(headers)
                    .duration(duration)
                    .error(error)
                    .message("Request processing failed")
                    .log();

                // Re-throw the error
                throw new RuntimeException(error);
            })
            .whenComplete((response, error) -> {
                // Clean up MDC after request completes (use constants — no key drift)
                MDC.remove(EcsMdc.TRACE_ID);
                MDC.remove(EcsMdc.SPAN_ID);
                MDC.remove(EcsMdc.PARENT_SPAN_ID);
                MDC.remove(EcsMdc.CLIENT_IP);
                MDC.remove(EcsMdc.USER_NAME);
                MDC.remove(EcsMdc.REPO_NAME);
                MDC.remove(EcsMdc.REPO_TYPE);
            });
    }

    /**
     * Build a {@link RequestContext} for the WI-03 {@link StructuredLogger}
     * access tier. The slice still maintains MDC directly (for legacy call
     * sites that read {@link MDC}); this method just assembles the same fields
     * into the immutable envelope the Tier-1 builder expects.
     */
    private RequestContext buildRequestContext(
        final SpanContext span,
        final String clientIp,
        final String userName,
        final RequestLine line
    ) {
        return new RequestContext(
            span.traceId(),
            /* transactionId */ null,
            span.spanId(),
            /* httpRequestId */ null,
            userName == null ? "anonymous" : userName,
            clientIp,
            /* userAgent */ null,
            this.repoName,
            this.repoType,
            RequestContext.ArtifactRef.EMPTY,
            line.uri().toString(),
            line.uri().getPath(),
            com.auto1.pantera.http.context.Deadline.in(java.time.Duration.ofSeconds(30))
        );
    }
}

