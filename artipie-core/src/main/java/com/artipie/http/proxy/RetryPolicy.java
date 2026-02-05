/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retry policy with exponential backoff for upstream requests.
 * Provides configurable retry logic with jitter to prevent thundering herd
 * on transient failures.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.builder()
 *     .maxAttempts(3)
 *     .initialDelay(Duration.ofMillis(100))
 *     .maxDelay(Duration.ofSeconds(5))
 *     .multiplier(2.0)
 *     .jitterFactor(0.25)
 *     .retryOn(e -> e instanceof java.net.ConnectException)
 *     .build();
 *
 * CompletableFuture<Response> result = policy.execute(
 *     () -> fetchFromUpstream(request)
 * );
 * }</pre>
 *
 * @since 1.0
 */
public final class RetryPolicy {

    /**
     * Default policy with sensible defaults for proxy operations.
     */
    public static final RetryPolicy DEFAULT = RetryPolicy.builder()
        .maxAttempts(3)
        .initialDelay(Duration.ofMillis(100))
        .maxDelay(Duration.ofSeconds(10))
        .multiplier(2.0)
        .jitterFactor(0.25)
        .retryOn(RetryPolicy::isRetryable)
        .build();

    /**
     * No retry - execute once and return result.
     */
    public static final RetryPolicy NO_RETRY = RetryPolicy.builder()
        .maxAttempts(1)
        .build();

    /**
     * Maximum number of attempts (including initial).
     */
    private final int maxAttempts;

    /**
     * Initial delay before first retry.
     */
    private final Duration initialDelay;

    /**
     * Maximum delay between retries.
     */
    private final Duration maxDelay;

    /**
     * Multiplier for exponential backoff.
     */
    private final double multiplier;

    /**
     * Jitter factor (0.0 to 1.0) to randomize delays.
     */
    private final double jitterFactor;

    /**
     * Predicate to determine if exception is retryable.
     */
    private final Predicate<Throwable> retryPredicate;

    private RetryPolicy(final Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterFactor = builder.jitterFactor;
        this.retryPredicate = builder.retryPredicate;
    }

    /**
     * Create a new builder.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Execute operation with retry policy.
     *
     * @param operation Operation to execute
     * @param <T> Result type
     * @return Future with result
     */
    public <T> CompletableFuture<T> execute(final Supplier<CompletableFuture<T>> operation) {
        return this.executeWithRetry(operation, 1, null);
    }

    /**
     * Execute operation with retry and context for metrics.
     *
     * @param operation Operation to execute
     * @param context Context for metrics (e.g., repository name)
     * @param <T> Result type
     * @return Future with result
     */
    public <T> CompletableFuture<T> execute(
        final Supplier<CompletableFuture<T>> operation,
        final RetryContext context
    ) {
        return this.executeWithRetry(operation, 1, context);
    }

    /**
     * Get maximum attempts configured.
     *
     * @return Max attempts
     */
    public int maxAttempts() {
        return this.maxAttempts;
    }

    /**
     * Calculate delay for specific attempt number.
     *
     * @param attempt Current attempt (1-based)
     * @return Delay duration with jitter applied
     */
    Duration delayForAttempt(final int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        // Exponential backoff: initialDelay * multiplier^(attempt-2)
        final double baseDelay = this.initialDelay.toMillis()
            * Math.pow(this.multiplier, attempt - 2);
        final long cappedDelay = Math.min((long) baseDelay, this.maxDelay.toMillis());

        // Add jitter
        final double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5)
            * 2 * this.jitterFactor;
        final long finalDelay = (long) (cappedDelay * jitter);

        return Duration.ofMillis(Math.max(0, finalDelay));
    }

    /**
     * Check if exception is retryable by default criteria.
     */
    private static boolean isRetryable(final Throwable error) {
        final Throwable cause = unwrap(error);
        // Retry on connection and timeout errors
        return cause instanceof java.net.ConnectException
            || cause instanceof java.net.SocketTimeoutException
            || cause instanceof java.net.UnknownHostException
            || cause instanceof java.util.concurrent.TimeoutException
            || cause instanceof java.io.IOException
            && cause.getMessage() != null
            && (cause.getMessage().contains("Connection reset")
                || cause.getMessage().contains("Connection refused")
                || cause.getMessage().contains("timed out"));
    }

    /**
     * Unwrap CompletionException to get actual cause.
     */
    private static Throwable unwrap(final Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Internal recursive execution with retry logic.
     */
    private <T> CompletableFuture<T> executeWithRetry(
        final Supplier<CompletableFuture<T>> operation,
        final int attempt,
        final RetryContext context
    ) {
        try {
            return operation.get().handle((result, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(result);
                }

                // Unwrap the exception
                final Throwable cause = unwrap(error);

                // Check if we should retry
                if (attempt >= this.maxAttempts || !this.retryPredicate.test(cause)) {
                    // Record metric for final failure
                    if (context != null) {
                        context.recordFailure(cause, attempt);
                    }
                    return CompletableFuture.<T>failedFuture(cause);
                }

                // Record metric for retry
                if (context != null) {
                    context.recordRetry(cause, attempt);
                }

                // Calculate delay and schedule retry
                final Duration delay = this.delayForAttempt(attempt + 1);
                if (delay.isZero()) {
                    return this.executeWithRetry(operation, attempt + 1, context);
                }
                return CompletableFuture.supplyAsync(
                    () -> (T) null,
                    CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                ).thenCompose(ignored -> this.executeWithRetry(operation, attempt + 1, context));
            }).thenCompose(f -> f);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Internal exception for tracking retry attempts.
     */
    private static final class RetryException extends RuntimeException {
        private final int attempt;

        RetryException(final Throwable cause, final int attempt) {
            super(cause);
            this.attempt = attempt;
        }
    }

    /**
     * Builder for RetryPolicy.
     */
    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(10);
        private double multiplier = 2.0;
        private double jitterFactor = 0.25;
        private Predicate<Throwable> retryPredicate = RetryPolicy::isRetryable;

        private Builder() {
        }

        /**
         * Set maximum number of attempts (including initial).
         *
         * @param attempts Max attempts (must be >= 1)
         * @return This builder
         */
        public Builder maxAttempts(final int attempts) {
            if (attempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = attempts;
            return this;
        }

        /**
         * Set initial delay before first retry.
         *
         * @param delay Initial delay
         * @return This builder
         */
        public Builder initialDelay(final Duration delay) {
            this.initialDelay = Objects.requireNonNull(delay);
            return this;
        }

        /**
         * Set maximum delay between retries.
         *
         * @param delay Maximum delay
         * @return This builder
         */
        public Builder maxDelay(final Duration delay) {
            this.maxDelay = Objects.requireNonNull(delay);
            return this;
        }

        /**
         * Set multiplier for exponential backoff.
         *
         * @param mult Multiplier (must be >= 1.0)
         * @return This builder
         */
        public Builder multiplier(final double mult) {
            if (mult < 1.0) {
                throw new IllegalArgumentException("multiplier must be >= 1.0");
            }
            this.multiplier = mult;
            return this;
        }

        /**
         * Set jitter factor to randomize delays.
         *
         * @param factor Jitter factor (0.0 to 1.0)
         * @return This builder
         */
        public Builder jitterFactor(final double factor) {
            if (factor < 0.0 || factor > 1.0) {
                throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
            }
            this.jitterFactor = factor;
            return this;
        }

        /**
         * Set predicate to determine if exception is retryable.
         *
         * @param predicate Retry predicate
         * @return This builder
         */
        public Builder retryOn(final Predicate<Throwable> predicate) {
            this.retryPredicate = Objects.requireNonNull(predicate);
            return this;
        }

        /**
         * Build the RetryPolicy.
         *
         * @return RetryPolicy instance
         */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }

    /**
     * Context for recording retry metrics.
     */
    public interface RetryContext {
        /**
         * Record a retry attempt.
         *
         * @param error Error that triggered retry
         * @param attempt Current attempt number
         */
        void recordRetry(Throwable error, int attempt);

        /**
         * Record final failure after all retries exhausted.
         *
         * @param error Final error
         * @param attempts Total attempts made
         */
        void recordFailure(Throwable error, int attempts);
    }
}
