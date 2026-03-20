/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import com.auto1.pantera.http.log.EcsLogger;

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

        EcsLogger.info("com.auto1.pantera.metrics.MicrometerMetrics")
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
}

