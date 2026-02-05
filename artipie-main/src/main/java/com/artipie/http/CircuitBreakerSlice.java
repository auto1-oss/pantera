/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.rq.RequestLine;
import com.artipie.metrics.ArtipieMetrics;
import com.artipie.metrics.MicrometerMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Slice decorator with circuit breaker pattern for upstream protection.
 *
 * <p>This prevents cascade failures when an upstream becomes unhealthy by:
 * <ul>
 *   <li>Tracking failure rates per upstream</li>
 *   <li>Opening the circuit when failures exceed threshold</li>
 *   <li>Fast-failing new requests when circuit is open</li>
 *   <li>Periodically allowing test requests when half-open</li>
 *   <li>Closing circuit when upstream recovers</li>
 * </ul>
 *
 * <p>Default configuration:
 * <ul>
 *   <li>Failure rate threshold: 50%</li>
 *   <li>Slow call rate threshold: 80%</li>
 *   <li>Slow call duration: 30 seconds</li>
 *   <li>Wait duration in open state: 60 seconds</li>
 *   <li>Sliding window size: 10 calls</li>
 *   <li>Minimum number of calls: 5</li>
 * </ul>
 *
 * @since 1.18.26
 */
public final class CircuitBreakerSlice implements Slice {

    /**
     * Registry for circuit breakers per upstream.
     */
    private static final CircuitBreakerRegistry REGISTRY = createRegistry();

    /**
     * Cache for circuit breaker instances per upstream name.
     */
    private static final ConcurrentHashMap<String, CircuitBreaker> BREAKERS = new ConcurrentHashMap<>();

    /**
     * Origin slice to wrap.
     */
    private final Slice origin;

    /**
     * Upstream name for circuit breaker identification.
     */
    private final String upstreamName;

    /**
     * The circuit breaker instance for this slice.
     */
    private final CircuitBreaker circuitBreaker;

    /**
     * Constructor with default circuit breaker settings.
     *
     * @param origin Origin slice to wrap
     * @param upstreamName Name of the upstream (used for circuit breaker identification)
     */
    public CircuitBreakerSlice(final Slice origin, final String upstreamName) {
        this.origin = origin;
        this.upstreamName = upstreamName;
        this.circuitBreaker = BREAKERS.computeIfAbsent(
            upstreamName,
            name -> {
                final CircuitBreaker cb = REGISTRY.circuitBreaker(name);
                // Register state change listener for monitoring
                cb.getEventPublisher().onStateTransition(event -> {
                    final String fromState = event.getStateTransition().getFromState().name();
                    final String toState = event.getStateTransition().getToState().name();
                    EcsLogger.warn("com.artipie.http.CircuitBreakerSlice")
                        .message("Circuit breaker state changed: " + name + " " + fromState + " -> " + toState)
                        .eventCategory("http")
                        .eventAction("circuit_breaker_state_change")
                        .eventOutcome(toState.toLowerCase())
                        .field("event.reason", "Circuit breaker " + name + " transitioned from " + fromState + " to " + toState)
                        .log();

                    // Record metric
                    recordCircuitBreakerState(name, toState);
                });
                return cb;
            }
        );
    }

    /**
     * Constructor with custom circuit breaker config.
     *
     * @param origin Origin slice to wrap
     * @param upstreamName Name of the upstream
     * @param config Custom circuit breaker configuration
     */
    public CircuitBreakerSlice(
        final Slice origin,
        final String upstreamName,
        final CircuitBreakerConfig config
    ) {
        this.origin = origin;
        this.upstreamName = upstreamName;
        this.circuitBreaker = CircuitBreaker.of(upstreamName, config);
        // Register state change listener for monitoring
        this.circuitBreaker.getEventPublisher().onStateTransition(event -> {
            final String fromState = event.getStateTransition().getFromState().name();
            final String toState = event.getStateTransition().getToState().name();
            EcsLogger.warn("com.artipie.http.CircuitBreakerSlice")
                .message("Circuit breaker state changed: " + upstreamName + " " + fromState + " -> " + toState)
                .eventCategory("http")
                .eventAction("circuit_breaker_state_change")
                .eventOutcome(toState.toLowerCase())
                .field("event.reason", "Circuit breaker " + upstreamName + " transitioned from " + fromState + " to " + toState)
                .log();

            recordCircuitBreakerState(upstreamName, toState);
        });
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Check if circuit is open - fail fast if so
        if (!this.circuitBreaker.tryAcquirePermission()) {
            final String requestId = line.method() + " " + line.uri().getPath();
            EcsLogger.warn("com.artipie.http.CircuitBreakerSlice")
                .message("Circuit breaker OPEN - rejecting request to " + this.upstreamName + ": " + requestId)
                .eventCategory("http")
                .eventAction("circuit_breaker_reject")
                .eventOutcome("failure")
                .field("http.request.id", requestId)
                .field("event.reason", "Circuit breaker for " + this.upstreamName + " is OPEN - upstream considered unhealthy")
                .log();

            recordCircuitBreakerRejection(this.upstreamName);

            // Return 503 Service Unavailable
            return CompletableFuture.completedFuture(
                ResponseBuilder.serviceUnavailable("Service temporarily unavailable - upstream " + this.upstreamName + " is unhealthy")
                    .header("Retry-After", "60")
                    .header("X-Circuit-Breaker", this.upstreamName + ":OPEN")
                    .build()
            );
        }

        final long startTime = System.currentTimeMillis();
        final String requestId = line.method() + " " + line.uri().getPath();

        return this.origin.response(line, headers, body)
            .whenComplete((response, error) -> {
                final long elapsed = System.currentTimeMillis() - startTime;

                if (error != null) {
                    // Record failure in circuit breaker
                    this.circuitBreaker.onError(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS, error);
                    recordCircuitBreakerCall(this.upstreamName, "failure", elapsed);

                    // Log the failure
                    final boolean isTimeout = error instanceof TimeoutException ||
                        (error.getCause() != null && error.getCause() instanceof TimeoutException);

                    EcsLogger.warn("com.artipie.http.CircuitBreakerSlice")
                        .message("Request failed for " + this.upstreamName + ": " + requestId +
                            (isTimeout ? " (timeout)" : ""))
                        .eventCategory("http")
                        .eventAction("circuit_breaker_failure")
                        .eventOutcome("failure")
                        .field("http.request.id", requestId)
                        .duration(elapsed)
                        .error(error)
                        .log();
                } else {
                    // Check response status
                    final int statusCode = response.status().code();
                    if (statusCode >= 500) {
                        // Server errors count as failures
                        this.circuitBreaker.onError(
                            elapsed,
                            java.util.concurrent.TimeUnit.MILLISECONDS,
                            new RuntimeException("HTTP " + statusCode)
                        );
                        recordCircuitBreakerCall(this.upstreamName, "server_error", elapsed);
                    } else {
                        // Success (including 4xx which are client errors, not upstream failures)
                        this.circuitBreaker.onSuccess(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS);
                        recordCircuitBreakerCall(this.upstreamName, "success", elapsed);
                    }
                }
            });
    }

    /**
     * Get the current state of the circuit breaker.
     *
     * @return Circuit breaker state (CLOSED, OPEN, HALF_OPEN)
     */
    public CircuitBreaker.State getState() {
        return this.circuitBreaker.getState();
    }

    /**
     * Get circuit breaker metrics.
     *
     * @return Circuit breaker metrics
     */
    public CircuitBreaker.Metrics getMetrics() {
        return this.circuitBreaker.getMetrics();
    }

    /**
     * Reset the circuit breaker to CLOSED state.
     * Use with caution - typically for admin/recovery operations.
     */
    public void reset() {
        this.circuitBreaker.reset();
        EcsLogger.info("com.artipie.http.CircuitBreakerSlice")
            .message("Circuit breaker manually reset: " + this.upstreamName)
            .eventCategory("http")
            .eventAction("circuit_breaker_reset")
            .log();
    }

    /**
     * Create the default circuit breaker registry with appropriate settings.
     *
     * @return Circuit breaker registry
     */
    private static CircuitBreakerRegistry createRegistry() {
        final CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            // Open circuit when 50% of calls fail
            .failureRateThreshold(50)
            // Also open when 80% of calls are slow
            .slowCallRateThreshold(80)
            // Calls taking > 30s are considered slow
            .slowCallDurationThreshold(Duration.ofSeconds(30))
            // Wait 60s before transitioning from OPEN to HALF_OPEN
            .waitDurationInOpenState(Duration.ofSeconds(60))
            // Use sliding window of last 10 calls
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            // Need at least 5 calls before calculating failure rate
            .minimumNumberOfCalls(5)
            // Allow 3 test calls in HALF_OPEN state
            .permittedNumberOfCallsInHalfOpenState(3)
            // Automatically transition from OPEN to HALF_OPEN
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            // Record these exceptions as failures
            .recordExceptions(
                TimeoutException.class,
                java.io.IOException.class,
                java.net.ConnectException.class,
                java.net.SocketTimeoutException.class
            )
            // Ignore these exceptions (don't count as failures)
            .ignoreExceptions(
                IllegalArgumentException.class
            )
            .build();

        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Record circuit breaker state change metric.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordCircuitBreakerState(final String upstream, final String state) {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordCircuitBreakerState(upstream, state);
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }

    /**
     * Record circuit breaker call metric.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordCircuitBreakerCall(final String upstream, final String result, final long durationMs) {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordCircuitBreakerCall(upstream, result, durationMs);
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }

    /**
     * Record circuit breaker rejection metric.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordCircuitBreakerRejection(final String upstream) {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordCircuitBreakerRejection(upstream);
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }
}
