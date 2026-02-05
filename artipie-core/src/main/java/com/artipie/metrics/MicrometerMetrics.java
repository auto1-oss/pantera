/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import com.artipie.http.log.EcsLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Micrometer metrics for Artipie.
 * Provides comprehensive observability across all repository types, caches, and upstreams.
 * Uses Micrometer with Prometheus registry for pull-based metrics collection.
 *
 * @since 1.20.2
 */
public final class MicrometerMetrics {

    private static volatile MicrometerMetrics instance;

    private final MeterRegistry registry;

    // === Active request tracking ===
    private final AtomicLong activeRequests = new AtomicLong(0);

    // === Upstream availability tracking ===
    private final Map<String, AtomicLong> upstreamAvailability = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();

    private MicrometerMetrics(final MeterRegistry registry) {
        this.registry = registry;

        // Register active requests gauge
        Gauge.builder("artipie.http.active.requests", activeRequests, AtomicLong::get)
            .description("Currently active HTTP requests")
            .register(registry);

        EcsLogger.info("com.artipie.metrics.MicrometerMetrics")
            .message("Micrometer metrics initialized with Prometheus registry")
            .eventCategory("configuration")
            .eventAction("micrometer_metrics_init")
            .eventOutcome("success")
            .log();
    }

    /**
     * Initialize metrics with a MeterRegistry.
     * Should be called once during application startup.
     *
     * @param registry The MeterRegistry to use
     */
    public static void initialize(final MeterRegistry registry) {
        if (instance == null) {
            synchronized (MicrometerMetrics.class) {
                if (instance == null) {
                    instance = new MicrometerMetrics(registry);
                }
            }
        }
    }

    /**
     * Get the singleton instance.
     *
     * @return MicrometerMetrics instance
     * @throws IllegalStateException if not initialized
     */
    public static MicrometerMetrics getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MicrometerMetrics not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Check if metrics are initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Get the MeterRegistry.
     *
     * @return MeterRegistry instance
     */
    public MeterRegistry getRegistry() {
        return this.registry;
    }

    /**
     * Get Prometheus scrape content (if using PrometheusMeterRegistry).
     *
     * @return Prometheus format metrics
     */
    public String getPrometheusMetrics() {
        if (this.registry instanceof PrometheusMeterRegistry) {
            return ((PrometheusMeterRegistry) this.registry).scrape();
        }
        return "";
    }

    // ========== HTTP Request Metrics ==========

    /**
     * Record HTTP request without repository context (legacy method).
     * @param method HTTP method
     * @param statusCode HTTP status code
     * @param durationMs Request duration in milliseconds
     * @deprecated Use {@link #recordHttpRequest(String, String, long, String, String)} instead
     */
    @Deprecated
    public void recordHttpRequest(String method, String statusCode, long durationMs) {
        recordHttpRequest(method, statusCode, durationMs, null, null);
    }

    /**
     * Record HTTP request with repository context.
     * @param method HTTP method
     * @param statusCode HTTP status code
     * @param durationMs Request duration in milliseconds
     * @param repoName Repository name (null if not in repository context)
     * @param repoType Repository type (null if not in repository context)
     */
    public void recordHttpRequest(String method, String statusCode, long durationMs,
                                   String repoName, String repoType) {
        // Build tags conditionally
        final String[] tags;
        if (repoName != null && repoType != null) {
            tags = new String[]{"method", method, "status_code", statusCode,
                               "repo_name", repoName, "repo_type", repoType};
        } else {
            tags = new String[]{"method", method, "status_code", statusCode};
        }

        Counter.builder("artipie.http.requests")
            .description("Total HTTP requests")
            .tags(tags)
            .register(registry)
            .increment();

        Timer.builder("artipie.http.request.duration")
            .description("HTTP request duration")
            .tags(tags)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordHttpRequestSize(String method, long bytes) {
        DistributionSummary.builder("artipie.http.request.size.bytes")
            .description("HTTP request body size")
            .tags("method", method)
            .baseUnit("bytes")
            .register(registry)
            .record(bytes);
    }

    public void recordHttpResponseSize(String method, String statusCode, long bytes) {
        DistributionSummary.builder("artipie.http.response.size.bytes")
            .description("HTTP response body size")
            .tags("method", method, "status_code", statusCode)
            .baseUnit("bytes")
            .register(registry)
            .record(bytes);
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }

    // ========== Repository Operation Metrics ==========

    /**
     * Record repository bytes downloaded (total traffic).
     * @param repoName Repository name
     * @param repoType Repository type
     * @param bytes Bytes downloaded
     */
    public void recordRepoBytesDownloaded(String repoName, String repoType, long bytes) {
        Counter.builder("artipie.repo.bytes.downloaded")
            .description("Total bytes downloaded from repository")
            .tags("repo_name", repoName, "repo_type", repoType)
            .baseUnit("bytes")
            .register(registry)
            .increment(bytes);
    }

    /**
     * Record repository bytes uploaded (total traffic).
     * @param repoName Repository name
     * @param repoType Repository type
     * @param bytes Bytes uploaded
     */
    public void recordRepoBytesUploaded(String repoName, String repoType, long bytes) {
        Counter.builder("artipie.repo.bytes.uploaded")
            .description("Total bytes uploaded to repository")
            .tags("repo_name", repoName, "repo_type", repoType)
            .baseUnit("bytes")
            .register(registry)
            .increment(bytes);
    }

    public void recordDownload(String repoName, String repoType, long sizeBytes) {
        Counter.builder("artipie.artifact.downloads")
            .description("Artifact download count")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .increment();

        if (sizeBytes > 0) {
            DistributionSummary.builder("artipie.artifact.size.bytes")
                .description("Artifact size distribution")
                .tags("repo_name", repoName, "repo_type", repoType, "operation", "download")
                .baseUnit("bytes")
                .register(registry)
                .record(sizeBytes);

            // Also record total traffic
            recordRepoBytesDownloaded(repoName, repoType, sizeBytes);
        }
    }

    public void recordUpload(String repoName, String repoType, long sizeBytes) {
        Counter.builder("artipie.artifact.uploads")
            .description("Artifact upload count")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .increment();

        if (sizeBytes > 0) {
            DistributionSummary.builder("artipie.artifact.size.bytes")
                .description("Artifact size distribution")
                .tags("repo_name", repoName, "repo_type", repoType, "operation", "upload")
                .baseUnit("bytes")
                .register(registry)
                .record(sizeBytes);

            // Also record total traffic
            recordRepoBytesUploaded(repoName, repoType, sizeBytes);
        }
    }

    public void recordMetadataOperation(String repoName, String repoType, String operation) {
        Counter.builder("artipie.metadata.operations")
            .description("Metadata operations count")
            .tags("repo_name", repoName, "repo_type", repoType, "operation", operation)
            .register(registry)
            .increment();
    }

    public void recordMetadataGenerationDuration(String repoName, String repoType, long durationMs) {
        Timer.builder("artipie.metadata.generation.duration")
            .description("Metadata generation duration")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Cache Metrics ==========

    public void recordCacheHit(String cacheType, String cacheTier) {
        Counter.builder("artipie.cache.requests")
            .description("Cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "result", "hit")
            .register(registry)
            .increment();
    }

    public void recordCacheMiss(String cacheType, String cacheTier) {
        Counter.builder("artipie.cache.requests")
            .description("Cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "result", "miss")
            .register(registry)
            .increment();
    }

    public void recordCacheEviction(String cacheType, String cacheTier, String reason) {
        Counter.builder("artipie.cache.evictions")
            .description("Cache evictions")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "reason", reason)
            .register(registry)
            .increment();
    }

    public void recordCacheError(String cacheType, String cacheTier, String errorType) {
        Counter.builder("artipie.cache.errors")
            .description("Cache errors")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "error_type", errorType)
            .register(registry)
            .increment();
    }

    public void recordCacheOperationDuration(String cacheType, String cacheTier, String operation, long durationMs) {
        Timer.builder("artipie.cache.operation.duration")
            .description("Cache operation latency")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "operation", operation)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordCacheDeduplication(String cacheType, String cacheTier) {
        Counter.builder("artipie.cache.deduplications")
            .description("Deduplicated cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier)
            .register(registry)
            .increment();
    }



    // ========== Storage Metrics ==========

    public void recordStorageOperation(String operation, String result, long durationMs) {
        Counter.builder("artipie.storage.operations")
            .description("Storage operations count")
            .tags("operation", operation, "result", result)
            .register(registry)
            .increment();

        Timer.builder("artipie.storage.operation.duration")
            .description("Storage operation duration")
            .tags("operation", operation, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Proxy & Upstream Metrics ==========

    public void recordProxyRequest(String repoName, String upstream, String result, long durationMs) {
        Counter.builder("artipie.proxy.requests")
            .description("Proxy upstream requests")
            .tags("repo_name", repoName, "upstream", upstream, "result", result)
            .register(registry)
            .increment();

        Timer.builder("artipie.proxy.request.duration")
            .description("Proxy upstream request duration")
            .tags("repo_name", repoName, "upstream", upstream, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordUpstreamLatency(String upstream, String result, long durationMs) {
        Timer.builder("artipie.upstream.latency")
            .description("Upstream request latency")
            .tags("upstream", upstream, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordUpstreamError(String repoName, String upstream, String errorType) {
        Counter.builder("artipie.upstream.errors")
            .description("Upstream errors")
            .tags("repo_name", repoName, "upstream", upstream, "error_type", errorType)
            .register(registry)
            .increment();

        // Track consecutive failures
        consecutiveFailures.computeIfAbsent(upstream, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordUpstreamSuccess(String upstream) {
        // Reset consecutive failures on success
        AtomicLong failures = consecutiveFailures.get(upstream);
        if (failures != null) {
            failures.set(0);
        }
    }

    public void setUpstreamAvailability(String upstream, boolean available) {
        upstreamAvailability.computeIfAbsent(upstream, k -> {
            AtomicLong gauge = new AtomicLong(0);
            Gauge.builder("artipie.upstream.available", gauge, AtomicLong::get)
                .description("Upstream availability (1=available, 0=unavailable)")
                .tags("upstream", upstream)
                .register(registry);
            return gauge;
        }).set(available ? 1 : 0);
    }

    // ========== Group Repository Metrics ==========

    public void recordGroupRequest(String groupName, String result) {
        Counter.builder("artipie.group.requests")
            .description("Group repository requests")
            .tags("group_name", groupName, "result", result)
            .register(registry)
            .increment();
    }

    public void recordGroupMemberRequest(String groupName, String memberName, String result) {
        Counter.builder("artipie.group.member.requests")
            .description("Group member requests")
            .tags("group_name", groupName, "member_name", memberName, "result", result)
            .register(registry)
            .increment();
    }

    public void recordGroupMemberLatency(String groupName, String memberName, String result, long durationMs) {
        Timer.builder("artipie.group.member.latency")
            .description("Group member request latency")
            .tags("group_name", groupName, "member_name", memberName, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordGroupResolutionDuration(String groupName, long durationMs) {
        Timer.builder("artipie.group.resolution.duration")
            .description("Group resolution duration")
            .tags("group_name", groupName)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Timeout & Streaming Metrics (Stall Prevention) ==========

    /**
     * Record request timeout event.
     * Tracks when TimeoutSlice fires during body streaming (the fix for the stall issue).
     *
     * @param phase Phase where timeout occurred (response_future, body_streaming)
     * @param elapsedMs Elapsed time before timeout in milliseconds
     */
    public void recordRequestTimeout(String phase, long elapsedMs) {
        Counter.builder("artipie.request.timeout")
            .description("Request timeouts - indicates potential stall prevention")
            .tags("phase", phase)
            .register(registry)
            .increment();

        Timer.builder("artipie.request.timeout.elapsed")
            .description("Elapsed time when timeout fired")
            .tags("phase", phase)
            .register(registry)
            .record(java.time.Duration.ofMillis(elapsedMs));
    }

    /**
     * Record body streaming completion.
     * Tracks the full lifecycle of body streaming (critical for monitoring the stall fix).
     *
     * @param bytes Bytes streamed
     * @param durationMs Duration of body streaming in milliseconds
     * @param result Result: success, error, cancelled
     */
    public void recordBodyStreaming(long bytes, long durationMs, String result) {
        Counter.builder("artipie.body.streaming.completed")
            .description("Body streaming completions")
            .tags("result", result)
            .register(registry)
            .increment();

        if (bytes > 0) {
            DistributionSummary.builder("artipie.body.streaming.bytes")
                .description("Bytes streamed in body")
                .tags("result", result)
                .baseUnit("bytes")
                .register(registry)
                .record(bytes);
        }

        Timer.builder("artipie.body.streaming.duration")
            .description("Body streaming duration")
            .tags("result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Record stream closed event (HttpClosedException).
     * This was the root cause of the stall incident - upstream closing mid-stream.
     */
    public void recordStreamClosed() {
        Counter.builder("artipie.upstream.stream.closed")
            .description("Upstream stream closed unexpectedly (HttpClosedException) - potential stall trigger")
            .register(registry)
            .increment();
    }

    /**
     * Record response race condition detection.
     * Tracks "End has already been called" warnings - the symptom of the stall issue.
     *
     * @param requestId Request identifier
     * @param winner The code path that won the race (for debugging)
     */
    public void recordResponseRace(String requestId, String winner) {
        Counter.builder("artipie.response.race.detected")
            .description("Response race condition detected - multiple paths tried to end response")
            .tags("winner", winner)
            .register(registry)
            .increment();
    }

    // ========== Circuit Breaker Metrics ==========

    /**
     * Record circuit breaker state change.
     * Tracks when circuit opens/closes for upstream protection.
     *
     * @param upstream Upstream identifier (repo name or URL)
     * @param state State: closed, open, half_open
     */
    public void recordCircuitBreakerState(String upstream, String state) {
        // Use a gauge that can be updated
        Counter.builder("artipie.circuit.breaker.state.change")
            .description("Circuit breaker state changes")
            .tags("upstream", upstream, "state", state)
            .register(registry)
            .increment();
    }

    /**
     * Record circuit breaker call result.
     *
     * @param upstream Upstream identifier
     * @param result Result: success, failure, rejected (circuit open)
     * @param durationMs Call duration in milliseconds
     */
    public void recordCircuitBreakerCall(String upstream, String result, long durationMs) {
        Counter.builder("artipie.circuit.breaker.calls")
            .description("Circuit breaker protected calls")
            .tags("upstream", upstream, "result", result)
            .register(registry)
            .increment();

        if (!"rejected".equals(result)) {
            Timer.builder("artipie.circuit.breaker.call.duration")
                .description("Circuit breaker protected call duration")
                .tags("upstream", upstream, "result", result)
                .register(registry)
                .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    /**
     * Record when circuit breaker rejects a call (circuit is open).
     *
     * @param upstream Upstream identifier
     */
    public void recordCircuitBreakerRejection(String upstream) {
        Counter.builder("artipie.circuit.breaker.rejections")
            .description("Calls rejected because circuit is open")
            .tags("upstream", upstream)
            .register(registry)
            .increment();
    }

    // ========== Enterprise Proxy Metrics ==========

    /**
     * Record proxy retry attempt.
     *
     * @param repoName Repository name
     * @param upstream Upstream URL
     * @param attempt Retry attempt number
     */
    public void recordProxyRetry(String repoName, String upstream, int attempt) {
        Counter.builder("artipie.proxy.retries")
            .description("Proxy request retry attempts")
            .tags("repo_name", repoName, "upstream", upstream, "attempt", String.valueOf(attempt))
            .register(registry)
            .increment();
    }

    /**
     * Record in-flight request count as gauge.
     *
     * @param repoName Repository name
     * @param supplier Supplier for current in-flight count
     */
    public void registerInFlightGauge(String repoName, java.util.function.Supplier<Number> supplier) {
        Gauge.builder("artipie.proxy.inflight", supplier)
            .description("Currently in-flight proxy requests (request deduplication)")
            .tags("repo_name", repoName)
            .register(registry);
    }

    /**
     * Record request deduplication (waiter joined existing in-flight request).
     *
     * @param repoName Repository name
     * @param upstream Upstream URL
     */
    public void recordProxyDeduplication(String repoName, String upstream) {
        Counter.builder("artipie.proxy.deduplications")
            .description("Requests deduplicated (joined existing in-flight request)")
            .tags("repo_name", repoName, "upstream", upstream)
            .register(registry)
            .increment();
    }

    /**
     * Record backpressure queue event.
     *
     * @param repoName Repository name
     * @param result Result: queued, executed, rejected
     */
    public void recordBackpressure(String repoName, String result) {
        Counter.builder("artipie.proxy.backpressure")
            .description("Backpressure control events")
            .tags("repo_name", repoName, "result", result)
            .register(registry)
            .increment();
    }

    /**
     * Record backpressure queue wait time.
     *
     * @param repoName Repository name
     * @param durationMs Wait duration in milliseconds
     */
    public void recordBackpressureWait(String repoName, long durationMs) {
        Timer.builder("artipie.proxy.backpressure.wait")
            .description("Time spent waiting in backpressure queue")
            .tags("repo_name", repoName)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Register backpressure utilization gauge.
     *
     * @param repoName Repository name
     * @param supplier Supplier for utilization (0.0 to 1.0)
     */
    public void registerBackpressureUtilization(String repoName, java.util.function.Supplier<Number> supplier) {
        Gauge.builder("artipie.proxy.backpressure.utilization", supplier)
            .description("Backpressure utilization (active/max concurrent)")
            .tags("repo_name", repoName)
            .register(registry);
    }

    /**
     * Register backpressure queue depth gauge.
     *
     * @param repoName Repository name
     * @param supplier Supplier for queue depth
     */
    public void registerBackpressureQueueDepth(String repoName, java.util.function.Supplier<Number> supplier) {
        Gauge.builder("artipie.proxy.backpressure.queue", supplier)
            .description("Requests waiting in backpressure queue")
            .tags("repo_name", repoName)
            .register(registry);
    }

    /**
     * Record auto-block state change.
     *
     * @param repoName Repository name
     * @param upstream Upstream URL
     * @param blocked True if upstream is now blocked
     */
    public void recordAutoBlock(String repoName, String upstream, boolean blocked) {
        Counter.builder("artipie.proxy.autoblock.changes")
            .description("Auto-block state changes")
            .tags("repo_name", repoName, "upstream", upstream, "blocked", String.valueOf(blocked))
            .register(registry)
            .increment();
    }

    /**
     * Record request rejected due to auto-block.
     *
     * @param repoName Repository name
     * @param upstream Upstream URL
     */
    public void recordAutoBlockRejection(String repoName, String upstream) {
        Counter.builder("artipie.proxy.autoblock.rejections")
            .description("Requests rejected due to auto-blocked upstream")
            .tags("repo_name", repoName, "upstream", upstream)
            .register(registry)
            .increment();
    }

    /**
     * Record distributed lock operation (for cluster coordination).
     *
     * @param repoName Repository name
     * @param operation Operation: acquire, release, wait
     * @param result Result: success, timeout, error
     */
    public void recordDistributedLock(String repoName, String operation, String result) {
        Counter.builder("artipie.proxy.distributed.lock")
            .description("Distributed lock operations for request deduplication")
            .tags("repo_name", repoName, "operation", operation, "result", result)
            .register(registry)
            .increment();
    }
}

