/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.vertx;

import com.artipie.http.log.EcsLogger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker implementation for per-destination failure protection.
 * <p>
 * States:
 * - CLOSED: Normal operation, requests are allowed
 * - OPEN: Failures exceeded threshold, requests are rejected
 * - HALF_OPEN: Testing if service has recovered
 * <p>
 * Opens when failure rate exceeds threshold (default 80%) in sliding window.
 */
public final class CircuitBreaker {

    /**
     * Circuit breaker states.
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * Default failure rate threshold (80%).
     */
    public static final int DEFAULT_FAILURE_RATE_THRESHOLD = 80;

    /**
     * Default sliding window size.
     */
    public static final int DEFAULT_SLIDING_WINDOW_SIZE = 10;

    /**
     * Default timeout before half-open (30 seconds).
     */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * Default failure threshold.
     */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /**
     * Default success threshold to close.
     */
    public static final int DEFAULT_SUCCESS_THRESHOLD = 3;

    /**
     * Destination identifier for logging.
     */
    private final String destination;

    /**
     * Failure rate threshold percentage.
     */
    private final int failureRateThreshold;

    /**
     * Sliding window size for tracking requests.
     */
    private final int slidingWindowSize;

    /**
     * Timeout in ms before transitioning from OPEN to HALF_OPEN.
     */
    private final long timeoutMs;

    /**
     * Number of consecutive successes needed to close circuit.
     */
    private final int successThreshold;

    /**
     * Current state.
     */
    private final AtomicReference<State> state;

    /**
     * Timestamp when circuit opened.
     */
    private final AtomicLong openedAt;

    /**
     * Sliding window for tracking success/failure.
     * Each bit represents success (0) or failure (1).
     */
    private final AtomicLong slidingWindow;

    /**
     * Number of requests in the sliding window.
     */
    private final AtomicInteger windowCount;

    /**
     * Consecutive successes in HALF_OPEN state.
     */
    private final AtomicInteger halfOpenSuccesses;

    /**
     * Constructor with defaults.
     *
     * @param destination Destination identifier (host:port)
     */
    public CircuitBreaker(final String destination) {
        this(
            destination,
            DEFAULT_FAILURE_RATE_THRESHOLD,
            DEFAULT_SLIDING_WINDOW_SIZE,
            DEFAULT_TIMEOUT_MS,
            DEFAULT_SUCCESS_THRESHOLD
        );
    }

    /**
     * Full constructor.
     *
     * @param destination Destination identifier
     * @param failureRateThreshold Failure rate % to open circuit
     * @param slidingWindowSize Number of requests to track
     * @param timeoutMs Timeout before half-open
     * @param successThreshold Successes needed to close
     */
    public CircuitBreaker(
        final String destination,
        final int failureRateThreshold,
        final int slidingWindowSize,
        final long timeoutMs,
        final int successThreshold
    ) {
        this.destination = destination;
        this.failureRateThreshold = failureRateThreshold;
        this.slidingWindowSize = Math.min(slidingWindowSize, 64); // Max 64 for long bitmask
        this.timeoutMs = timeoutMs;
        this.successThreshold = successThreshold;
        this.state = new AtomicReference<>(State.CLOSED);
        this.openedAt = new AtomicLong(0);
        this.slidingWindow = new AtomicLong(0);
        this.windowCount = new AtomicInteger(0);
        this.halfOpenSuccesses = new AtomicInteger(0);
    }

    /**
     * Check if a request should be allowed.
     *
     * @return true if request is allowed, false if rejected
     */
    public boolean allowRequest() {
        final State current = this.state.get();

        switch (current) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if timeout has elapsed
                if (System.currentTimeMillis() - this.openedAt.get() >= this.timeoutMs) {
                    // Transition to HALF_OPEN
                    if (this.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        this.halfOpenSuccesses.set(0);
                        EcsLogger.info("com.artipie.http.client")
                            .message("Circuit breaker HALF_OPEN")
                            .eventCategory("http")
                            .eventAction("circuit_breaker")
                            .eventOutcome("half_open")
                            .field("url.domain", this.destination)
                            .log();
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                // Allow limited requests in half-open state
                return true;

            default:
                return true;
        }
    }

    /**
     * Record a successful request.
     */
    public void recordSuccess() {
        final State current = this.state.get();

        switch (current) {
            case CLOSED:
                // Add success to sliding window (bit 0)
                this.addToWindow(false);
                break;

            case HALF_OPEN:
                final int successes = this.halfOpenSuccesses.incrementAndGet();
                if (successes >= this.successThreshold) {
                    // Close the circuit
                    if (this.state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        this.resetWindow();
                        EcsLogger.info("com.artipie.http.client")
                            .message("Circuit breaker CLOSED")
                            .eventCategory("http")
                            .eventAction("circuit_breaker")
                            .eventOutcome("closed")
                            .field("url.domain", this.destination)
                            .log();
                    }
                }
                break;

            default:
                break;
        }
    }

    /**
     * Record a failed request.
     */
    public void recordFailure() {
        final State current = this.state.get();

        switch (current) {
            case CLOSED:
                // Add failure to sliding window (bit 1)
                this.addToWindow(true);
                // Check if we should open the circuit
                if (this.shouldOpen()) {
                    if (this.state.compareAndSet(State.CLOSED, State.OPEN)) {
                        this.openedAt.set(System.currentTimeMillis());
                        EcsLogger.warn("com.artipie.http.client")
                            .message(String.format("Circuit breaker OPEN (failure_rate=%.2f)", this.currentFailureRate()))
                            .eventCategory("http")
                            .eventAction("circuit_breaker")
                            .eventOutcome("open")
                            .field("url.domain", this.destination)
                            .log();
                    }
                }
                break;

            case HALF_OPEN:
                // Single failure in half-open state opens the circuit again
                if (this.state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    this.openedAt.set(System.currentTimeMillis());
                    EcsLogger.warn("com.artipie.http.client")
                        .message("Circuit breaker OPEN (from half-open)")
                        .eventCategory("http")
                        .eventAction("circuit_breaker")
                        .eventOutcome("open")
                        .field("url.domain", this.destination)
                        .log();
                }
                break;

            default:
                break;
        }
    }

    /**
     * Add a result to the sliding window.
     *
     * @param failure true for failure, false for success
     */
    private void addToWindow(final boolean failure) {
        // Shift window left and add new bit
        this.slidingWindow.updateAndGet(window -> {
            long shifted = window << 1;
            if (failure) {
                shifted |= 1L;
            }
            // Mask to keep only slidingWindowSize bits
            return shifted & ((1L << this.slidingWindowSize) - 1);
        });

        // Update count (max = slidingWindowSize)
        this.windowCount.updateAndGet(count -> Math.min(count + 1, this.slidingWindowSize));
    }

    /**
     * Reset the sliding window.
     */
    private void resetWindow() {
        this.slidingWindow.set(0);
        this.windowCount.set(0);
    }

    /**
     * Check if the circuit should open based on failure rate.
     *
     * @return true if should open
     */
    private boolean shouldOpen() {
        final int count = this.windowCount.get();
        if (count < this.slidingWindowSize) {
            // Not enough samples yet
            return false;
        }
        return this.currentFailureRate() >= this.failureRateThreshold;
    }

    /**
     * Calculate current failure rate percentage.
     *
     * @return Failure rate (0-100)
     */
    private int currentFailureRate() {
        final int count = this.windowCount.get();
        if (count == 0) {
            return 0;
        }
        final long window = this.slidingWindow.get();
        final int failures = Long.bitCount(window & ((1L << count) - 1));
        return (failures * 100) / count;
    }

    /**
     * Get current state.
     *
     * @return Current state
     */
    public State state() {
        return this.state.get();
    }

    /**
     * Get destination identifier.
     *
     * @return Destination
     */
    public String destination() {
        return this.destination;
    }

    /**
     * Create a disabled circuit breaker (always allows requests).
     * Uses 100% failure threshold which means it never opens.
     *
     * @param destination Destination identifier
     * @return Disabled circuit breaker
     */
    public static CircuitBreaker disabled(final String destination) {
        // 100% failure threshold = never opens, 1 window = minimal tracking
        return new CircuitBreaker(destination, 101, 1, Long.MAX_VALUE, 1);
    }

    /**
     * Builder for CircuitBreaker.
     */
    public static final class Builder {
        private String destination;
        private int failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
        private int slidingWindowSize = DEFAULT_SLIDING_WINDOW_SIZE;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private int successThreshold = DEFAULT_SUCCESS_THRESHOLD;

        public Builder destination(final String value) {
            this.destination = value;
            return this;
        }

        public Builder failureRateThreshold(final int value) {
            this.failureRateThreshold = value;
            return this;
        }

        public Builder slidingWindowSize(final int value) {
            this.slidingWindowSize = value;
            return this;
        }

        public Builder timeoutMs(final long value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder successThreshold(final int value) {
            this.successThreshold = value;
            return this;
        }

        public CircuitBreaker build() {
            if (this.destination == null || this.destination.isEmpty()) {
                throw new IllegalStateException("Destination is required");
            }
            return new CircuitBreaker(
                this.destination,
                this.failureRateThreshold,
                this.slidingWindowSize,
                this.timeoutMs,
                this.successThreshold
            );
        }
    }

    /**
     * Create a new builder.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
