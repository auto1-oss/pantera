/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.log.EcsLogEvent;
import com.artipie.http.rq.RequestLine;
import org.slf4j.MDC;

import java.util.UUID;
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
 * <p>This slice should be used at the top level of the slice chain to ensure
 * all HTTP requests are logged consistently.
 * 
 * @since 1.18.24
 */
public final class EcsLoggingSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Remote address (client IP).
     */
    private final String remoteAddress;

    /**
     * Ctor.
     * @param origin Origin slice
     */
    public EcsLoggingSlice(final Slice origin) {
        this(origin, "unknown");
    }

    /**
     * Ctor with remote address.
     * @param origin Origin slice
     * @param remoteAddress Remote client address
     */
    public EcsLoggingSlice(final Slice origin, final String remoteAddress) {
        this.origin = origin;
        this.remoteAddress = remoteAddress;
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
        
        // Generate trace.id if not already set (e.g., from incoming X-Request-ID header)
        String traceId = MDC.get("trace.id");
        if (traceId == null || traceId.isEmpty()) {
            // Check for incoming trace headers
            for (var h : headers.find("x-request-id")) {
                traceId = h.getValue();
                break;
            }
            if (traceId == null || traceId.isEmpty()) {
                for (var h : headers.find("x-trace-id")) {
                    traceId = h.getValue();
                    break;
                }
            }
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
        }
        
        // Set MDC context for this request thread
        MDC.put("trace.id", traceId);
        MDC.put("client.ip", clientIp);
        if (userName != null) {
            MDC.put("user.name", userName);
        }

        return this.origin.response(line, headers, body)
            .thenApply(response -> {
                final long duration = System.currentTimeMillis() - startTime;

                // Build ECS log event
                final EcsLogEvent logEvent = new EcsLogEvent()
                    .httpMethod(line.method().value())
                    .httpVersion(line.version())
                    .httpStatus(response.status())
                    .urlPath(line.uri().getPath())
                    .clientIp(clientIp)
                    .userAgent(headers)
                    .duration(duration);

                // Add query string if present
                final String query = line.uri().getQuery();
                if (query != null && !query.isEmpty()) {
                    logEvent.urlQuery(query);
                }

                // Add username if available from authentication
                if (userName != null) {
                    logEvent.userName(userName);
                }

                // Log the event (automatically selects log level based on status)
                logEvent.log();

                return response;
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;

                // Log error with ECS fields
                new EcsLogEvent()
                    .httpMethod(line.method().value())
                    .httpVersion(line.version())
                    .urlPath(line.uri().getPath())
                    .clientIp(clientIp)
                    .userAgent(headers)
                    .duration(duration)
                    .error(error)
                    .message("Request processing failed")
                    .log();

                // Re-throw the error
                throw new RuntimeException(error);
            })
            .whenComplete((response, error) -> {
                // Clean up MDC after request completes
                MDC.remove("trace.id");
                MDC.remove("client.ip");
                MDC.remove("user.name");
            });
    }
}

