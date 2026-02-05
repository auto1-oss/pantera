/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RetryPolicy}.
 */
class RetryPolicyTest {

    @Test
    void successfulOperationDoesNotRetry() throws Exception {
        final AtomicInteger attempts = new AtomicInteger(0);
        final RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .build();

        final String result = policy.execute(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture("success");
        }).join();

        assertEquals("success", result);
        assertEquals(1, attempts.get(), "Should only execute once on success");
    }

    @Test
    void retriesOnRetryableException() throws Exception {
        final AtomicInteger attempts = new AtomicInteger(0);
        final RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(1))
            .build();

        final String result = policy.execute(() -> {
            final int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            }
            return CompletableFuture.completedFuture("success");
        }).join();

        assertEquals("success", result);
        assertEquals(3, attempts.get(), "Should retry until success");
    }

    @Test
    void stopsAfterMaxAttempts() {
        final AtomicInteger attempts = new AtomicInteger(0);
        final RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(1))
            .build();

        assertThrows(java.util.concurrent.CompletionException.class, () ->
            policy.execute(() -> {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            }).join()
        );

        assertEquals(3, attempts.get(), "Should stop after max attempts");
    }

    @Test
    void doesNotRetryOnNonRetryableException() {
        final AtomicInteger attempts = new AtomicInteger(0);
        final RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .retryOn(e -> e instanceof ConnectException)
            .build();

        assertThrows(java.util.concurrent.CompletionException.class, () ->
            policy.execute(() -> {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalArgumentException("Not retryable"));
            }).join()
        );

        assertEquals(1, attempts.get(), "Should not retry non-retryable exceptions");
    }

    @Test
    void retriesOnTimeoutException() throws Exception {
        final AtomicInteger attempts = new AtomicInteger(0);
        final RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ofMillis(1))
            .build();

        final String result = policy.execute(() -> {
            final int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(new TimeoutException("Request timed out"));
            }
            return CompletableFuture.completedFuture("success");
        }).join();

        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void noRetryPolicyExecutesOnce() throws Exception {
        final AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(java.util.concurrent.CompletionException.class, () ->
            RetryPolicy.NO_RETRY.execute(() -> {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            }).join()
        );

        assertEquals(1, attempts.get(), "NO_RETRY should execute exactly once");
    }

    @Test
    void delayCalculationWithExponentialBackoff() {
        final RetryPolicy policy = RetryPolicy.builder()
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(10))
            .multiplier(2.0)
            .jitterFactor(0)  // No jitter for deterministic test
            .build();

        // Attempt 1: no delay (first attempt)
        assertEquals(Duration.ZERO, policy.delayForAttempt(1));

        // Attempt 2: 100ms initial delay
        assertEquals(Duration.ofMillis(100), policy.delayForAttempt(2));

        // Attempt 3: 100ms * 2 = 200ms
        assertEquals(Duration.ofMillis(200), policy.delayForAttempt(3));

        // Attempt 4: 200ms * 2 = 400ms
        assertEquals(Duration.ofMillis(400), policy.delayForAttempt(4));
    }

    @Test
    void delayCappedAtMaxDelay() {
        final RetryPolicy policy = RetryPolicy.builder()
            .initialDelay(Duration.ofSeconds(5))
            .maxDelay(Duration.ofSeconds(10))
            .multiplier(3.0)
            .jitterFactor(0)
            .build();

        // Attempt 3 would be 5s * 3 = 15s, but capped at 10s
        assertEquals(Duration.ofSeconds(10), policy.delayForAttempt(3));
    }

    @Test
    void delayWithJitter() {
        final RetryPolicy policy = RetryPolicy.builder()
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(10))
            .multiplier(2.0)
            .jitterFactor(0.25)
            .build();

        // With 25% jitter, delay should be within 75%-125% of base
        final Duration delay = policy.delayForAttempt(2);
        assertTrue(delay.toMillis() >= 75 && delay.toMillis() <= 125,
            "Delay should be within jitter range: " + delay.toMillis());
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().maxAttempts(0).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().multiplier(0.5).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().jitterFactor(-0.1).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().jitterFactor(1.5).build()
        );
    }
}
