/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.vertx;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * Retry policy with exponential backoff and jitter.
 * <p>
 * Delays: initialDelay * multiplier^attempt + jitter
 * Example with defaults: 100ms -> 200ms -> 400ms (±20% jitter)
 */
public final class RetryPolicy {

    /**
     * Default max attempts.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * Default initial delay in milliseconds.
     */
    public static final long DEFAULT_INITIAL_DELAY = 100L;

    /**
     * Default max delay in milliseconds.
     */
    public static final long DEFAULT_MAX_DELAY = 1000L;

    /**
     * Default multiplier.
     */
    public static final double DEFAULT_MULTIPLIER = 2.0;

    /**
     * Default jitter factor (±20%).
     */
    public static final double DEFAULT_JITTER = 0.2;

    /**
     * Default retryable status codes.
     */
    public static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

    /**
     * Maximum number of retry attempts.
     */
    private final int maxAttempts;

    /**
     * Initial delay in milliseconds.
     */
    private final long initialDelay;

    /**
     * Maximum delay in milliseconds.
     */
    private final long maxDelay;

    /**
     * Multiplier for exponential backoff.
     */
    private final double multiplier;

    /**
     * Jitter factor (e.g., 0.2 for ±20%).
     */
    private final double jitter;

    /**
     * HTTP status codes that should trigger a retry.
     */
    private final Set<Integer> retryableStatusCodes;

    /**
     * Constructor with defaults.
     */
    public RetryPolicy() {
        this(
            DEFAULT_MAX_ATTEMPTS,
            DEFAULT_INITIAL_DELAY,
            DEFAULT_MAX_DELAY,
            DEFAULT_MULTIPLIER,
            DEFAULT_JITTER,
            DEFAULT_RETRYABLE_STATUS_CODES
        );
    }

    /**
     * Full constructor.
     *
     * @param maxAttempts Maximum retry attempts
     * @param initialDelay Initial delay in ms
     * @param maxDelay Maximum delay in ms
     * @param multiplier Exponential multiplier
     * @param jitter Jitter factor (0.2 = ±20%)
     * @param retryableStatusCodes HTTP status codes to retry
     */
    public RetryPolicy(
        final int maxAttempts,
        final long initialDelay,
        final long maxDelay,
        final double multiplier,
        final double jitter,
        final Set<Integer> retryableStatusCodes
    ) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.jitter = jitter;
        this.retryableStatusCodes = retryableStatusCodes;
    }

    /**
     * Calculate the delay before the next retry attempt.
     *
     * @param attempt Current attempt number (0-based)
     * @return Delay in milliseconds
     */
    public long nextDelay(final int attempt) {
        // Calculate base delay with exponential backoff
        final double baseDelay = this.initialDelay * Math.pow(this.multiplier, attempt);

        // Apply max delay cap
        final double cappedDelay = Math.min(baseDelay, this.maxDelay);

        // Apply jitter (±jitter%)
        final double jitterRange = cappedDelay * this.jitter;
        final double jitterOffset = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterRange;

        return Math.max(0, Math.round(cappedDelay + jitterOffset));
    }

    /**
     * Determine if a request should be retried.
     *
     * @param attempt Current attempt number (0-based)
     * @param error The error that occurred (may be null)
     * @param statusCode HTTP status code (-1 if not available)
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(final int attempt, final Throwable error, final int statusCode) {
        // Check if we've exceeded max attempts
        if (attempt >= this.maxAttempts - 1) {
            return false;
        }

        // Retry on retryable status codes
        if (statusCode > 0 && this.retryableStatusCodes.contains(statusCode)) {
            return true;
        }

        // Retry on transient connection errors
        if (error != null && isRetryableError(error)) {
            return true;
        }

        return false;
    }

    /**
     * Check if an error is retryable.
     *
     * @param error The error to check
     * @return true if retryable
     */
    private static boolean isRetryableError(final Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException
                || current instanceof SocketTimeoutException
                || current instanceof TimeoutException
                || current instanceof UnknownHostException
                || (current instanceof IOException
                    && current.getMessage() != null
                    && (current.getMessage().contains("Connection reset")
                        || current.getMessage().contains("Connection refused")
                        || current.getMessage().contains("Connection timed out")))) {
                return true;
            }
            // Check for Vert.x specific timeout
            if (current.getClass().getName().contains("TimeoutException")
                || current.getClass().getName().contains("NoStackTraceThrowable")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Get max attempts.
     * @return Max attempts
     */
    public int maxAttempts() {
        return this.maxAttempts;
    }

    /**
     * Get initial delay.
     * @return Initial delay in ms
     */
    public long initialDelay() {
        return this.initialDelay;
    }

    /**
     * Get max delay.
     * @return Max delay in ms
     */
    public long maxDelay() {
        return this.maxDelay;
    }

    /**
     * Get multiplier.
     * @return Multiplier
     */
    public double multiplier() {
        return this.multiplier;
    }

    /**
     * Get jitter factor.
     * @return Jitter factor
     */
    public double jitter() {
        return this.jitter;
    }

    /**
     * Get retryable status codes.
     * @return Set of status codes
     */
    public Set<Integer> retryableStatusCodes() {
        return this.retryableStatusCodes;
    }

    /**
     * Builder for RetryPolicy.
     */
    public static final class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long initialDelay = DEFAULT_INITIAL_DELAY;
        private long maxDelay = DEFAULT_MAX_DELAY;
        private double multiplier = DEFAULT_MULTIPLIER;
        private double jitter = DEFAULT_JITTER;
        private Set<Integer> retryableStatusCodes = DEFAULT_RETRYABLE_STATUS_CODES;

        public Builder maxAttempts(final int value) {
            this.maxAttempts = value;
            return this;
        }

        public Builder initialDelay(final long value) {
            this.initialDelay = value;
            return this;
        }

        public Builder maxDelay(final long value) {
            this.maxDelay = value;
            return this;
        }

        public Builder multiplier(final double value) {
            this.multiplier = value;
            return this;
        }

        public Builder jitter(final double value) {
            this.jitter = value;
            return this;
        }

        public Builder retryableStatusCodes(final Set<Integer> value) {
            this.retryableStatusCodes = value;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(
                this.maxAttempts,
                this.initialDelay,
                this.maxDelay,
                this.multiplier,
                this.jitter,
                this.retryableStatusCodes
            );
        }
    }

    /**
     * Create a new builder.
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a disabled retry policy (no retries).
     * @return RetryPolicy with 0 retries
     */
    public static RetryPolicy disabled() {
        return new RetryPolicy(0, 0, 0, 1.0, 0, Set.of());
    }
}
