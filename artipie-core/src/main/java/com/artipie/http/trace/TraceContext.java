/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.trace;

import org.slf4j.MDC;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Distributed tracing context for correlating logs across request flows.
 * Implements Elastic ECS tracing with trace.id field.
 * 
 * <p>Usage:
 * <pre>{@code
 * // Generate or extract trace ID at request entry point
 * String traceId = TraceContext.extractOrGenerate(headers);
 * 
 * // Execute with trace context
 * TraceContext.withTrace(traceId, () -> {
 *     // All logs in this block will have trace.id
 *     LOGGER.info("Processing request");
 *     return someOperation();
 * });
 * }</pre>
 *
 * @since 1.18.19
 */
public final class TraceContext {

    /**
     * MDC key for trace ID (Elastic ECS standard).
     */
    public static final String TRACE_ID_KEY = "trace.id";

    /**
     * HTTP header for trace ID propagation (W3C Trace Context standard).
     */
    public static final String TRACE_PARENT_HEADER = "traceparent";

    /**
     * Alternative header for trace ID (X-Trace-Id).
     */
    public static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * Alternative header for trace ID (X-Request-Id).
     */
    public static final String X_REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * Secure random for generating trace IDs.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Private constructor - utility class.
     */
    private TraceContext() {
    }

    /**
     * Generate a new trace ID.
     * Format: 16 bytes (128 bits) encoded as base64url (22 characters).
     * Compatible with Elastic APM and OpenTelemetry.
     *
     * @return New trace ID
     */
    public static String generateTraceId() {
        final byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Extract trace ID from headers or generate a new one.
     * Checks headers in order: traceparent, X-Trace-Id, X-Request-Id.
     *
     * @param headers Request headers
     * @return Trace ID (extracted or generated)
     */
    public static String extractOrGenerate(final com.artipie.http.Headers headers) {
        // Try W3C Trace Context format: traceparent: 00-<trace-id>-<span-id>-<flags>
        final Optional<String> traceparent = headers.stream()
            .filter(h -> TRACE_PARENT_HEADER.equalsIgnoreCase(h.getKey()))
            .map(com.artipie.http.headers.Header::getValue)
            .findFirst();
        
        if (traceparent.isPresent()) {
            final String[] parts = traceparent.get().split("-");
            if (parts.length >= 2) {
                return parts[1]; // Extract trace-id part
            }
        }

        // Try X-Trace-Id header
        final Optional<String> xTraceId = headers.stream()
            .filter(h -> X_TRACE_ID_HEADER.equalsIgnoreCase(h.getKey()))
            .map(com.artipie.http.headers.Header::getValue)
            .findFirst();
        
        if (xTraceId.isPresent() && !xTraceId.get().trim().isEmpty()) {
            return xTraceId.get().trim();
        }

        // Try X-Request-Id header
        final Optional<String> xRequestId = headers.stream()
            .filter(h -> X_REQUEST_ID_HEADER.equalsIgnoreCase(h.getKey()))
            .map(com.artipie.http.headers.Header::getValue)
            .findFirst();
        
        if (xRequestId.isPresent() && !xRequestId.get().trim().isEmpty()) {
            return xRequestId.get().trim();
        }

        // Generate new trace ID
        return generateTraceId();
    }

    /**
     * Get current trace ID from MDC.
     *
     * @return Current trace ID or empty if not set
     */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(TRACE_ID_KEY));
    }

    /**
     * Set trace ID in MDC for current thread.
     *
     * @param traceId Trace ID to set
     */
    public static void set(final String traceId) {
        if (traceId != null && !traceId.trim().isEmpty()) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
    }

    /**
     * Clear trace ID from MDC.
     */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    /**
     * Execute a runnable with trace context.
     * Ensures trace ID is set before execution and cleaned up after.
     *
     * @param traceId Trace ID
     * @param runnable Code to execute
     */
    public static void withTrace(final String traceId, final Runnable runnable) {
        final String previousTraceId = MDC.get(TRACE_ID_KEY);
        try {
            set(traceId);
            runnable.run();
        } finally {
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                clear();
            }
        }
    }

    /**
     * Execute a supplier with trace context.
     *
     * @param traceId Trace ID
     * @param supplier Code to execute
     * @param <T> Return type
     * @return Result from supplier
     */
    public static <T> T withTrace(final String traceId, final Supplier<T> supplier) {
        final String previousTraceId = MDC.get(TRACE_ID_KEY);
        try {
            set(traceId);
            return supplier.get();
        } finally {
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                clear();
            }
        }
    }

    /**
     * Execute a callable with trace context.
     *
     * @param traceId Trace ID
     * @param callable Code to execute
     * @param <T> Return type
     * @return Result from callable
     * @throws Exception If callable throws
     */
    public static <T> T withTraceCallable(final String traceId, final Callable<T> callable) throws Exception {
        final String previousTraceId = MDC.get(TRACE_ID_KEY);
        try {
            set(traceId);
            return callable.call();
        } finally {
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                clear();
            }
        }
    }

    /**
     * Wrap a CompletionStage to propagate trace context.
     * The trace ID will be set when the stage completes.
     *
     * @param traceId Trace ID
     * @param stage Completion stage
     * @param <T> Result type
     * @return Wrapped completion stage with trace context
     */
    public static <T> CompletionStage<T> wrapWithTrace(
        final String traceId,
        final CompletionStage<T> stage
    ) {
        return stage.thenApply(result -> {
            set(traceId);
            return result;
        }).exceptionally(error -> {
            set(traceId);
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        });
    }

    /**
     * Wrap a CompletionStage transformation to propagate trace context.
     *
     * @param traceId Trace ID
     * @param function Transformation function
     * @param <T> Input type
     * @param <U> Output type
     * @return Function that executes with trace context
     */
    public static <T, U> Function<T, U> withTraceFunction(
        final String traceId,
        final Function<T, U> function
    ) {
        return input -> withTrace(traceId, () -> function.apply(input));
    }

    /**
     * Create a CompletableFuture that runs with trace context.
     *
     * @param traceId Trace ID
     * @param supplier Supplier to execute
     * @param <T> Result type
     * @return CompletableFuture with trace context
     */
    public static <T> CompletableFuture<T> supplyAsyncWithTrace(
        final String traceId,
        final Supplier<T> supplier
    ) {
        return CompletableFuture.supplyAsync(() -> withTrace(traceId, supplier));
    }
}
