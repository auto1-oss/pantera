/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit Breaker pattern for upstream repositories.
 * Prevents hammering failed upstream by failing fast after threshold.
 * 
 * <p>States:
 * <ul>
 *   <li>CLOSED: Normal operation, requests pass through</li>
 *   <li>OPEN: Too many failures, fail fast without calling upstream</li>
 *   <li>HALF_OPEN: Testing if upstream recovered, single request allowed</li>
 * </ul>
 * 
 * @since 1.0
 */
public final class CircuitBreakerSlice implements Slice {

    /**
     * Circuit breaker state.
     */
    enum State {
        /**
         * Normal operation.
         */
        CLOSED,

        /**
         * Failing fast.
         */
        OPEN,

        /**
         * Testing recovery.
         */
        HALF_OPEN
    }

    /**
     * Default failure threshold before opening circuit.
     */
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /**
     * Default timeout before trying again.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    /**
     * Origin slice (upstream).
     */
    private final Slice origin;

    /**
     * Current circuit state.
     */
    private final AtomicReference<State> state;

    /**
     * Consecutive failure count.
     */
    private final AtomicInteger failureCount;

    /**
     * Timestamp of last failure.
     */
    private final AtomicLong lastFailureTime;

    /**
     * Failure threshold before opening circuit.
     */
    private final int failureThreshold;

    /**
     * Timeout before retrying (millis).
     */
    private final long timeoutMillis;

    /**
     * Constructor with defaults.
     * @param origin Origin slice
     */
    public CircuitBreakerSlice(final Slice origin) {
        this(origin, DEFAULT_FAILURE_THRESHOLD, DEFAULT_TIMEOUT);
    }

    /**
     * Constructor with custom settings.
     * @param origin Origin slice
     * @param failureThreshold Failures before opening circuit
     * @param timeout Timeout before retrying
     */
    public CircuitBreakerSlice(
        final Slice origin,
        final int failureThreshold,
        final Duration timeout
    ) {
        this.origin = origin;
        this.state = new AtomicReference<>(State.CLOSED);
        this.failureCount = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        this.failureThreshold = failureThreshold;
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final State currentState = this.state.get();

        // Check if circuit is open
        if (currentState == State.OPEN) {
            final long timeSinceFailure = System.currentTimeMillis() - this.lastFailureTime.get();

            if (timeSinceFailure > this.timeoutMillis) {
                // Timeout expired - try half-open
                Logger.info(
                    this,
                    "Circuit breaker half-open after %dms, testing upstream",
                    timeSinceFailure
                );
                this.state.compareAndSet(State.OPEN, State.HALF_OPEN);
            } else {
                // Still open - fail fast
                Logger.debug(
                    this,
                    "Circuit breaker OPEN, failing fast (%d failures, %dms since last)",
                    this.failureCount.get(),
                    timeSinceFailure
                );
                return CompletableFuture.completedFuture(
                    ResponseBuilder.serviceUnavailable(
                        "Circuit breaker open - upstream unavailable"
                    ).build()
                );
            }
        }

        // Try request
        return this.origin.response(line, headers, body)
            .handle((resp, error) -> {
                if (error != null) {
                    // Request failed
                    onFailure(error);
                    throw new CompletionException(error);
                }

                // Check response status
                final int statusCode = resp.status().code();
                if (statusCode >= 500 && statusCode < 600) {
                    // Server error - count as failure
                    onFailure(new IllegalStateException("HTTP " + statusCode));
                    throw new CompletionException(
                        new IllegalStateException("Upstream error: " + statusCode)
                    );
                }

                // Success
                onSuccess();
                return resp;
            });
    }

    /**
     * Handle successful request.
     */
    private void onSuccess() {
        final int failures = this.failureCount.getAndSet(0);

        final State currentState = this.state.get();
        if (currentState == State.HALF_OPEN) {
            // Recovery successful
            this.state.compareAndSet(State.HALF_OPEN, State.CLOSED);
            Logger.info(
                this,
                "Circuit breaker CLOSED - upstream recovered (was %d failures)",
                failures
            );
        } else if (failures > 0) {
            // Reset failure count
            Logger.debug(
                this,
                "Circuit breaker reset failure count from %d to 0",
                failures
            );
        }
    }

    /**
     * Handle failed request.
     * @param error Error that occurred
     */
    private void onFailure(final Throwable error) {
        final int failures = this.failureCount.incrementAndGet();
        this.lastFailureTime.set(System.currentTimeMillis());

        if (failures >= this.failureThreshold) {
            // Open circuit
            final boolean wasOpen = this.state.getAndSet(State.OPEN) == State.OPEN;
            if (!wasOpen) {
                Logger.warn(
                    this,
                    "Circuit breaker OPENED after %d failures: %s",
                    failures,
                    error.getMessage()
                );
            }
        } else {
            Logger.debug(
                this,
                "Circuit breaker failure %d/%d: %s",
                failures,
                this.failureThreshold,
                error.getMessage()
            );
        }
    }

    /**
     * Get current circuit state (for testing/monitoring).
     * @return Current state
     */
    public State getState() {
        return this.state.get();
    }

    /**
     * Get current failure count (for testing/monitoring).
     * @return Failure count
     */
    public int getFailureCount() {
        return this.failureCount.get();
    }
}
