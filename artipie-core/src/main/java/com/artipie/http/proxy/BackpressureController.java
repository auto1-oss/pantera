/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Backpressure controller for limiting concurrent upstream requests.
 * Prevents connection pool exhaustion and provides graceful degradation
 * when upstream is slow or overloaded.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Semaphore-based concurrent request limiting</li>
 *   <li>Configurable queue timeout (fail-fast vs wait)</li>
 *   <li>Metrics for monitoring (active, queued, rejected counts)</li>
 *   <li>Fair ordering to prevent starvation</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * BackpressureController controller = new BackpressureController(
 *     50,  // max concurrent requests
 *     Duration.ofSeconds(30),  // queue timeout
 *     "npm-proxy"  // name for metrics
 * );
 *
 * CompletableFuture<Response> result = controller.execute(
 *     () -> fetchFromUpstream(request)
 * );
 * }</pre>
 *
 * @since 1.0
 */
public final class BackpressureController {

    /**
     * Default maximum concurrent requests.
     */
    public static final int DEFAULT_MAX_CONCURRENT = 50;

    /**
     * Default queue timeout.
     */
    public static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Semaphore for limiting concurrent requests.
     */
    private final Semaphore semaphore;

    /**
     * Maximum concurrent requests allowed.
     */
    private final int maxConcurrent;

    /**
     * Timeout for waiting to acquire permit.
     */
    private final Duration queueTimeout;

    /**
     * Controller name for metrics.
     */
    private final String name;

    /**
     * Count of currently active requests.
     */
    private final AtomicLong activeCount;

    /**
     * Count of requests currently waiting in queue.
     */
    private final AtomicLong queuedCount;

    /**
     * Total count of rejected requests (timeout).
     */
    private final AtomicLong rejectedCount;

    /**
     * Total count of completed requests.
     */
    private final AtomicLong completedCount;

    /**
     * Create controller with default settings.
     *
     * @param name Controller name for metrics
     */
    public BackpressureController(final String name) {
        this(DEFAULT_MAX_CONCURRENT, DEFAULT_QUEUE_TIMEOUT, name);
    }

    /**
     * Create controller with custom settings.
     *
     * @param maxConcurrent Maximum concurrent requests
     * @param queueTimeout Timeout for waiting to acquire permit
     * @param name Controller name for metrics
     */
    public BackpressureController(
        final int maxConcurrent,
        final Duration queueTimeout,
        final String name
    ) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        this.maxConcurrent = maxConcurrent;
        this.queueTimeout = queueTimeout;
        this.name = name;
        // Use fair semaphore to prevent starvation
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.activeCount = new AtomicLong(0);
        this.queuedCount = new AtomicLong(0);
        this.rejectedCount = new AtomicLong(0);
        this.completedCount = new AtomicLong(0);
    }

    /**
     * Execute operation with backpressure control.
     * Acquires permit before execution, releases after completion.
     *
     * @param operation Operation to execute
     * @param <T> Result type
     * @return Future with result or BackpressureException if rejected
     */
    public <T> CompletableFuture<T> execute(final Supplier<CompletableFuture<T>> operation) {
        // Try to acquire permit without blocking first
        if (this.semaphore.tryAcquire()) {
            return this.executeWithPermit(operation);
        }

        // Need to queue - track it
        this.queuedCount.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            try {
                final boolean acquired = this.semaphore.tryAcquire(
                    this.queueTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
                );
                if (!acquired) {
                    this.rejectedCount.incrementAndGet();
                    throw new BackpressureException(
                        String.format(
                            "Backpressure limit reached for %s: %d concurrent requests, "
                                + "timeout after %dms",
                            this.name, this.maxConcurrent, this.queueTimeout.toMillis()
                        )
                    );
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.rejectedCount.incrementAndGet();
                throw new BackpressureException("Interrupted while waiting for permit", e);
            } finally {
                this.queuedCount.decrementAndGet();
            }
        }).thenCompose(acquired -> this.executeWithPermit(operation));
    }

    /**
     * Try to execute operation immediately without waiting.
     * Returns empty if no permit available.
     *
     * @param operation Operation to execute
     * @param <T> Result type
     * @return Future with result or empty if rejected
     */
    public <T> CompletableFuture<T> tryExecute(final Supplier<CompletableFuture<T>> operation) {
        if (!this.semaphore.tryAcquire()) {
            this.rejectedCount.incrementAndGet();
            return CompletableFuture.failedFuture(
                new BackpressureException(
                    String.format(
                        "Backpressure limit reached for %s: %d concurrent requests",
                        this.name, this.maxConcurrent
                    )
                )
            );
        }
        return this.executeWithPermit(operation);
    }

    /**
     * Get number of currently active requests.
     *
     * @return Active request count
     */
    public long activeCount() {
        return this.activeCount.get();
    }

    /**
     * Get number of requests currently waiting in queue.
     *
     * @return Queued request count
     */
    public long queuedCount() {
        return this.queuedCount.get();
    }

    /**
     * Get total number of rejected requests.
     *
     * @return Rejected request count
     */
    public long rejectedCount() {
        return this.rejectedCount.get();
    }

    /**
     * Get total number of completed requests.
     *
     * @return Completed request count
     */
    public long completedCount() {
        return this.completedCount.get();
    }

    /**
     * Get available permits.
     *
     * @return Available permit count
     */
    public int availablePermits() {
        return this.semaphore.availablePermits();
    }

    /**
     * Get maximum concurrent requests.
     *
     * @return Max concurrent
     */
    public int maxConcurrent() {
        return this.maxConcurrent;
    }

    /**
     * Get controller name.
     *
     * @return Name
     */
    public String name() {
        return this.name;
    }

    /**
     * Get utilization percentage (0.0 to 1.0).
     *
     * @return Utilization
     */
    public double utilization() {
        return (double) this.activeCount.get() / this.maxConcurrent;
    }

    /**
     * Execute operation with permit already acquired.
     */
    private <T> CompletableFuture<T> executeWithPermit(
        final Supplier<CompletableFuture<T>> operation
    ) {
        this.activeCount.incrementAndGet();
        try {
            return operation.get()
                .whenComplete((result, error) -> {
                    this.activeCount.decrementAndGet();
                    this.completedCount.incrementAndGet();
                    this.semaphore.release();
                });
        } catch (Exception e) {
            this.activeCount.decrementAndGet();
            this.semaphore.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Exception thrown when backpressure limit is reached.
     */
    public static final class BackpressureException extends RuntimeException {
        /**
         * Constructor with message.
         *
         * @param message Error message
         */
        public BackpressureException(final String message) {
            super(message);
        }

        /**
         * Constructor with message and cause.
         *
         * @param message Error message
         * @param cause Cause
         */
        public BackpressureException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
