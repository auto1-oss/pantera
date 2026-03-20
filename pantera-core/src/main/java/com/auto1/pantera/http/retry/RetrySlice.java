/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.retry;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Slice decorator that retries failed requests with exponential backoff.
 * <p>Retries on: 5xx status codes, connection timeouts, exceptions.
 * Does NOT retry on: 4xx client errors, successful responses.</p>
 *
 * @since 1.20.13
 */
public final class RetrySlice implements Slice {

    /**
     * Default max retries.
     */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /**
     * Default initial delay.
     */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);

    /**
     * Default backoff multiplier.
     */
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Maximum number of retry attempts.
     */
    private final int maxRetries;

    /**
     * Initial delay before first retry.
     */
    private final Duration initialDelay;

    /**
     * Backoff multiplier for subsequent retries.
     */
    private final double backoffMultiplier;

    /**
     * Constructor with defaults.
     * @param origin Slice to wrap
     */
    public RetrySlice(final Slice origin) {
        this(origin, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }

    /**
     * Constructor with custom configuration.
     * @param origin Slice to wrap
     * @param maxRetries Maximum retry attempts
     * @param initialDelay Initial delay before first retry
     * @param backoffMultiplier Multiplier for exponential backoff
     */
    public RetrySlice(
        final Slice origin,
        final int maxRetries,
        final Duration initialDelay,
        final double backoffMultiplier
    ) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.maxRetries = maxRetries;
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.attempt(line, headers, body, 0, this.initialDelay.toMillis());
    }

    /**
     * Attempt a request, retrying on failure with exponential backoff.
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @param attempt Current attempt number (0-based)
     * @param delayMs Current delay in milliseconds
     * @return Response future
     */
    private CompletableFuture<Response> attempt(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final int attempt,
        final long delayMs
    ) {
        return this.origin.response(line, headers, body)
            .<CompletableFuture<Response>>handle((response, error) -> {
                if (error != null) {
                    if (attempt < this.maxRetries) {
                        return this.delayedAttempt(
                            line, headers, body, attempt + 1,
                            (long) (delayMs * this.backoffMultiplier)
                        );
                    }
                    return CompletableFuture.failedFuture(error);
                }
                if (shouldRetry(response) && attempt < this.maxRetries) {
                    return this.delayedAttempt(
                        line, headers, body, attempt + 1,
                        (long) (delayMs * this.backoffMultiplier)
                    );
                }
                return CompletableFuture.completedFuture(response);
            })
            .thenCompose(Function.identity());
    }

    /**
     * Schedule a retry attempt after a delay with jitter.
     * Jitter prevents thundering herd by adding random 0-50% to the delay.
     */
    private CompletableFuture<Response> delayedAttempt(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final int attempt,
        final long delayMs
    ) {
        // Add jitter: delay * (1.0 + random[0, 0.5)) to prevent thundering herd
        final long jitteredDelay = (long) (delayMs
            * (1.0 + java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.5)));
        final Executor delayed = CompletableFuture.delayedExecutor(
            jitteredDelay, TimeUnit.MILLISECONDS
        );
        return CompletableFuture.supplyAsync(() -> null, delayed)
            .thenCompose(ignored -> this.attempt(line, headers, body, attempt, delayMs));
    }

    /**
     * Whether to retry based on response status.
     * @param response HTTP response
     * @return True if response indicates a retryable server error
     */
    private static boolean shouldRetry(final Response response) {
        return response.status().code() >= 500;
    }
}
