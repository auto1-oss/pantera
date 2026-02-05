/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import com.artipie.asto.Content;
import com.artipie.cache.DistributedInFlight;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Enterprise proxy slice that wraps an origin slice with production-grade features:
 * <ul>
 *   <li>Request deduplication (single-flight pattern)</li>
 *   <li>Retry with exponential backoff</li>
 *   <li>Backpressure control (concurrent request limiting)</li>
 *   <li>Auto-block for failing upstreams (circuit breaker)</li>
 *   <li>Configurable timeouts</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Slice origin = new PyProxySlice(clients, remote);
 * ProxyConfig config = ProxyConfig.builder()
 *     .retryMaxAttempts(3)
 *     .backpressureMaxConcurrent(50)
 *     .build();
 *
 * Slice enterprise = new EnterpriseProxySlice(
 *     origin,
 *     config,
 *     "pypi-proxy",
 *     "https://pypi.org",
 *     key -> storage.load(key)  // cache reader for waiters
 * );
 * }</pre>
 *
 * @since 1.0
 */
public final class EnterpriseProxySlice implements Slice {

    /**
     * Origin slice to wrap.
     */
    private final Slice origin;

    /**
     * Proxy configuration.
     */
    private final ProxyConfig config;

    /**
     * Repository name for metrics and namespacing.
     */
    private final String repoName;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Retry policy.
     */
    private final RetryPolicy retryPolicy;

    /**
     * Backpressure controller (optional).
     */
    private final Optional<BackpressureController> backpressure;

    /**
     * Auto-block service (optional).
     */
    private final Optional<AutoBlockService> autoBlock;

    /**
     * Distributed in-flight tracker for deduplication.
     */
    private final DistributedInFlight inFlight;

    /**
     * Function to read from cache for waiting requests.
     */
    private final Function<String, CompletableFuture<Optional<Content>>> cacheReader;

    /**
     * Create with default configuration.
     *
     * @param origin Origin slice
     * @param repoName Repository name
     * @param upstreamUrl Upstream URL
     * @param cacheReader Function to read from cache for waiters
     */
    public EnterpriseProxySlice(
        final Slice origin,
        final String repoName,
        final String upstreamUrl,
        final Function<String, CompletableFuture<Optional<Content>>> cacheReader
    ) {
        this(origin, ProxyConfig.DEFAULT, repoName, upstreamUrl, cacheReader);
    }

    /**
     * Create with custom configuration.
     *
     * @param origin Origin slice
     * @param config Proxy configuration
     * @param repoName Repository name
     * @param upstreamUrl Upstream URL
     * @param cacheReader Function to read from cache for waiters
     */
    public EnterpriseProxySlice(
        final Slice origin,
        final ProxyConfig config,
        final String repoName,
        final String upstreamUrl,
        final Function<String, CompletableFuture<Optional<Content>>> cacheReader
    ) {
        this.origin = origin;
        this.config = config;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
        this.cacheReader = cacheReader;
        this.retryPolicy = config.buildRetryPolicy();
        this.backpressure = config.buildBackpressureController(repoName);
        this.autoBlock = config.buildAutoBlockService();
        this.inFlight = new DistributedInFlight(
            repoName,
            config.deduplication().timeout()
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String key = line.uri().getPath();

        // Step 1: Check auto-block (circuit breaker)
        if (this.autoBlock.isPresent() && this.autoBlock.get().isBlocked(this.upstreamUrl)) {
            final long retryAfter = this.autoBlock.get()
                .remainingBlockTime(this.upstreamUrl)
                .getSeconds();
            return CompletableFuture.completedFuture(
                ResponseBuilder.serviceUnavailable("Upstream temporarily blocked due to failures")
                    .header("Retry-After", String.valueOf(Math.max(1, retryAfter)))
                    .build()
            );
        }

        // Step 2: Apply backpressure if configured
        if (this.backpressure.isPresent()) {
            return this.backpressure.get().execute(
                () -> this.executeWithDeduplication(line, headers, body, key)
            ).exceptionally(error -> {
                if (error.getCause() instanceof BackpressureController.BackpressureException) {
                    return ResponseBuilder.serviceUnavailable("Too many concurrent requests")
                        .header("Retry-After", "5")
                        .build();
                }
                throw new java.util.concurrent.CompletionException(error);
            });
        }

        return this.executeWithDeduplication(line, headers, body, key);
    }

    /**
     * Execute request with deduplication.
     */
    private CompletableFuture<Response> executeWithDeduplication(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String key
    ) {
        if (!this.config.deduplication().enabled()) {
            return this.executeWithRetry(line, headers, body);
        }

        return this.inFlight.tryAcquire(key).thenCompose(result -> {
            if (result.isWaiter()) {
                // Wait for leader, then read from cache
                return result.waitForLeader().thenCompose(success -> {
                    if (!success) {
                        // Leader failed - return error
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }
                    // Read from cache
                    return this.cacheReader.apply(key).thenApply(cached -> {
                        if (cached.isPresent()) {
                            return ResponseBuilder.ok()
                                .body(cached.get())
                                .build();
                        }
                        return ResponseBuilder.notFound().build();
                    });
                });
            }

            // We are the leader - execute with retry
            return this.executeWithRetry(line, headers, body)
                .whenComplete((response, error) -> {
                    final boolean success = error == null
                        && response != null
                        && response.status().success();
                    result.complete(success);
                });
        });
    }

    /**
     * Execute request with retry policy.
     */
    private CompletableFuture<Response> executeWithRetry(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.retryPolicy.execute(
            () -> this.executeOrigin(line, headers, body),
            new RetryPolicy.RetryContext() {
                @Override
                public void recordRetry(final Throwable error, final int attempt) {
                    EnterpriseProxySlice.this.recordRetryMetric(error, attempt);
                }

                @Override
                public void recordFailure(final Throwable error, final int attempts) {
                    EnterpriseProxySlice.this.recordFailureMetric(error, attempts);
                }
            }
        );
    }

    /**
     * Execute origin request with timeout and auto-block recording.
     */
    private CompletableFuture<Response> executeOrigin(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.origin.response(line, headers, body)
            .orTimeout(this.config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .thenApply(response -> {
                // Record success/failure for auto-block
                if (this.autoBlock.isPresent()) {
                    if (response.status().success()) {
                        this.autoBlock.get().recordSuccess(this.upstreamUrl);
                    } else if (response.status().code() >= 500) {
                        this.autoBlock.get().recordFailure(
                            this.upstreamUrl,
                            new RuntimeException("HTTP " + response.status().code())
                        );
                    }
                }
                return response;
            })
            .exceptionally(error -> {
                // Record failure for auto-block
                if (this.autoBlock.isPresent()) {
                    this.autoBlock.get().recordFailure(this.upstreamUrl, error);
                }
                throw new java.util.concurrent.CompletionException(error);
            });
    }

    /**
     * Record retry metric.
     */
    private void recordRetryMetric(final Throwable error, final int attempt) {
        this.recordMetricSafely(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRetry(this.repoName, this.upstreamUrl, attempt);
            }
        });
    }

    /**
     * Record failure metric.
     */
    private void recordFailureMetric(final Throwable error, final int attempts) {
        this.recordMetricSafely(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.repoName, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (ignore errors).
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetricSafely(final Runnable metric) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()) {
                metric.run();
            }
        } catch (Exception e) {
            // Ignore metric errors
        }
    }

    /**
     * Get backpressure controller for metrics access.
     *
     * @return Optional backpressure controller
     */
    public Optional<BackpressureController> backpressure() {
        return this.backpressure;
    }

    /**
     * Get auto-block service for metrics access.
     *
     * @return Optional auto-block service
     */
    public Optional<AutoBlockService> autoBlock() {
        return this.autoBlock;
    }

    /**
     * Get in-flight tracker for metrics access.
     *
     * @return DistributedInFlight tracker
     */
    public DistributedInFlight inFlight() {
        return this.inFlight;
    }
}
