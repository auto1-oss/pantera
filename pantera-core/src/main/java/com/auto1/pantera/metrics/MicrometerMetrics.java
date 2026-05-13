/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
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
 * Micrometer metrics for Pantera.
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
        Gauge.builder("pantera.http.active.requests", activeRequests, AtomicLong::get)
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

        Counter.builder("pantera.http.requests")
            .description("Total HTTP requests")
            .tags(tags)
            .register(registry)
            .increment();

        Timer.builder("pantera.http.request.duration")
            .description("HTTP request duration")
            .tags(tags)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordHttpRequestSize(String method, long bytes) {
        DistributionSummary.builder("pantera.http.request.size.bytes")
            .description("HTTP request body size")
            .tags("method", method)
            .baseUnit("bytes")
            .register(registry)
            .record(bytes);
    }

    public void recordHttpResponseSize(String method, String statusCode, long bytes) {
        DistributionSummary.builder("pantera.http.response.size.bytes")
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
        Counter.builder("pantera.repo.bytes.downloaded")
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
        Counter.builder("pantera.repo.bytes.uploaded")
            .description("Total bytes uploaded to repository")
            .tags("repo_name", repoName, "repo_type", repoType)
            .baseUnit("bytes")
            .register(registry)
            .increment(bytes);
    }

    public void recordDownload(String repoName, String repoType, long sizeBytes) {
        Counter.builder("pantera.artifact.downloads")
            .description("Artifact download count")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .increment();

        if (sizeBytes > 0) {
            DistributionSummary.builder("pantera.artifact.size.bytes")
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
        Counter.builder("pantera.artifact.uploads")
            .description("Artifact upload count")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .increment();

        if (sizeBytes > 0) {
            DistributionSummary.builder("pantera.artifact.size.bytes")
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
        Counter.builder("pantera.metadata.operations")
            .description("Metadata operations count")
            .tags("repo_name", repoName, "repo_type", repoType, "operation", operation)
            .register(registry)
            .increment();
    }

    public void recordMetadataGenerationDuration(String repoName, String repoType, long durationMs) {
        Timer.builder("pantera.metadata.generation.duration")
            .description("Metadata generation duration")
            .tags("repo_name", repoName, "repo_type", repoType)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Cache Metrics ==========

    public void recordCacheHit(String cacheType, String cacheTier) {
        Counter.builder("pantera.cache.requests")
            .description("Cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "result", "hit")
            .register(registry)
            .increment();
    }

    public void recordCacheMiss(String cacheType, String cacheTier) {
        Counter.builder("pantera.cache.requests")
            .description("Cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "result", "miss")
            .register(registry)
            .increment();
    }

    public void recordCacheEviction(String cacheType, String cacheTier, String reason) {
        Counter.builder("pantera.cache.evictions")
            .description("Cache evictions")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "reason", reason)
            .register(registry)
            .increment();
    }

    public void recordCacheError(String cacheType, String cacheTier, String errorType) {
        Counter.builder("pantera.cache.errors")
            .description("Cache errors")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "error_type", errorType)
            .register(registry)
            .increment();
    }

    public void recordCacheOperationDuration(String cacheType, String cacheTier, String operation, long durationMs) {
        Timer.builder("pantera.cache.operation.duration")
            .description("Cache operation latency")
            .tags("cache_type", cacheType, "cache_tier", cacheTier, "operation", operation)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordCacheDeduplication(String cacheType, String cacheTier) {
        Counter.builder("pantera.cache.deduplications")
            .description("Deduplicated cache requests")
            .tags("cache_type", cacheType, "cache_tier", cacheTier)
            .register(registry)
            .increment();
    }

    // ========== Publish-Date Registry Metrics ==========

    /**
     * Records one publish-date lookup outcome.
     *
     * @param repoType repo type ("maven", "npm", ...)
     * @param outcome  one of: l1_hit, l2_hit, source_hit, source_miss, source_error
     * @param durationMs total time spent in {@code publishDate(...)} for this call
     */
    public void recordPublishDateLookup(
        final String repoType, final String outcome, final long durationMs
    ) {
        Timer.builder("pantera.publish_date.lookup")
            .description("Publish-date registry lookup latency")
            .tags("repository.type", repoType, "outcome", outcome)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Storage Metrics ==========

    public void recordStorageOperation(String operation, String result, long durationMs) {
        Counter.builder("pantera.storage.operations")
            .description("Storage operations count")
            .tags("operation", operation, "result", result)
            .register(registry)
            .increment();

        Timer.builder("pantera.storage.operation.duration")
            .description("Storage operation duration")
            .tags("operation", operation, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    // ========== Proxy & Upstream Metrics ==========

    public void recordProxyRequest(String repoName, String upstream, String result, long durationMs) {
        Counter.builder("pantera.proxy.requests")
            .description("Proxy upstream requests")
            .tags("repo_name", repoName, "upstream", upstream, "result", result)
            .register(registry)
            .increment();

        Timer.builder("pantera.proxy.request.duration")
            .description("Proxy upstream request duration")
            .tags("repo_name", repoName, "upstream", upstream, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordUpstreamLatency(String upstream, String result, long durationMs) {
        Timer.builder("pantera.upstream.latency")
            .description("Upstream request latency")
            .tags("upstream", upstream, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordUpstreamError(String repoName, String upstream, String errorType) {
        Counter.builder("pantera.upstream.errors")
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
            Gauge.builder("pantera.upstream.available", gauge, AtomicLong::get)
                .description("Upstream availability (1=available, 0=unavailable)")
                .tags("upstream", upstream)
                .register(registry);
            return gauge;
        }).set(available ? 1 : 0);
    }

    // ========== Group Repository Metrics ==========

    public void recordGroupRequest(String groupName, String result) {
        Counter.builder("pantera.group.requests")
            .description("Group repository requests")
            .tags("group_name", groupName, "result", result)
            .register(registry)
            .increment();
    }

    public void recordGroupMemberRequest(String groupName, String memberName, String result) {
        Counter.builder("pantera.group.member.requests")
            .description("Group member requests")
            .tags("group_name", groupName, "member_name", memberName, "result", result)
            .register(registry)
            .increment();
    }

    public void recordGroupMemberLatency(String groupName, String memberName, String result, long durationMs) {
        Timer.builder("pantera.group.member.latency")
            .description("Group member request latency")
            .tags("group_name", groupName, "member_name", memberName, "result", result)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordGroupResolutionDuration(String groupName, long durationMs) {
        Timer.builder("pantera.group.resolution.duration")
            .description("Group resolution duration")
            .tags("group_name", groupName)
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Record the duration of a single phase inside the group-resolution
     * handler chain (Phase 7.5 profiler instrumentation).
     *
     * <p>Emits {@code pantera_handler_phase_seconds{group_name,phase}} so
     * dashboards can compute per-phase wall contribution as
     * {@code phase_sum / total_count}. Phases overlap when async work runs
     * concurrently — the sum across phases will exceed wall time when this
     * happens, which is itself a useful signal.
     *
     * @param groupName group repository name
     * @param phase     phase name (e.g. {@code "index_lookup"},
     *                  {@code "targeted_local_read"},
     *                  {@code "proxy_only_fanout"},
     *                  {@code "full_two_phase_fanout"})
     * @param durationNs phase duration in nanoseconds
     */
    public void recordHandlerPhaseDuration(
        final String groupName, final String phase, final long durationNs
    ) {
        Timer.builder("pantera.handler.phase")
            .description("Per-stage latency inside the group-resolution handler "
                + "(Phase 7.5 profiler instrumentation)")
            .tags("group_name", groupName, "phase", phase)
            .register(registry)
            .record(java.time.Duration.ofNanos(durationNs));
    }

    /**
     * Record per-phase latency on a proxy cache slice
     * ({@link com.auto1.pantera.http.cache.BaseCachedProxySlice}).
     *
     * <p>Emits {@code pantera_proxy_phase_seconds{repo_name,phase}} so we
     * can decompose the in-pantera per-request handler time on the
     * member-slice side (cache-hit serve, pre-process branch, fetch-direct,
     * etc.) — the missing half of the Phase 7.5 profiler.
     *
     * @param repoName  repository name (e.g. {@code "maven_proxy"})
     * @param phase     phase name (e.g. {@code "cache_first_flow"})
     * @param durationNs phase duration in nanoseconds
     */
    public void recordProxyPhaseDuration(
        final String repoName, final String phase, final long durationNs
    ) {
        Timer.builder("pantera.proxy.phase")
            .description("Per-stage latency inside the proxy cache slice "
                + "(Phase 7.5 profiler instrumentation)")
            .tags("repo_name", repoName, "phase", phase)
            .register(registry)
            .record(java.time.Duration.ofNanos(durationNs));
    }

    // ========== HTTP/2 Negotiation Metrics ==========

    /**
     * Record an upstream HTTP response for ALPN-protocol observability.
     *
     * <p>Increments {@code pantera_http2_negotiated_total{upstream_host,version}}
     * exactly once per upstream response received via {@code JettyClientSlice}.
     * The {@code version} label uses ALPN canonical names ({@code "h2"} for
     * HTTP/2, {@code "http/1.1"} for HTTP/1.1) so dashboards can compute the
     * h2-adoption ratio per upstream host.
     *
     * <p>Counter creation is idempotent — Micrometer returns the same counter
     * for the same name+tags tuple, so calling this on every response is safe
     * (the per-call cost is a small ConcurrentHashMap lookup keyed on the tag
     * tuple).
     *
     * @param upstreamHost the host of the upstream server (e.g. {@code "repo1.maven.org"})
     * @param version ALPN protocol identifier ({@code "h2"} or {@code "http/1.1"})
     */
    public void recordHttp2Negotiation(final String upstreamHost, final String version) {
        Counter.builder("pantera.http2.negotiated")
            .description("Upstream responses received, labelled by negotiated ALPN protocol")
            .tags("upstream_host", upstreamHost, "version", version)
            .register(registry)
            .increment();
    }

    // ========== M1 (Finding #8): Outbound observability foundation ==========
    //
    // Every outbound request (foreground proxy, cooldown HEAD, metadata
    // refresh — and historically prefetch, deleted in M2) increments one of
    // these counters. The amplification ratio
    //   sum(rate(pantera_upstream_requests_total[5m]))
    //     /
    //   sum(rate(pantera_http_requests_total{result="success"}[5m]))
    // is the primary gate the perf harness enforces: any sustained value
    // above 1.5 is a regression. See analysis/plan/v1/PLAN.md milestone M1.

    /**
     * Record an outbound HTTP request to an upstream host.
     *
     * <p>Emits {@code pantera_upstream_requests_total{upstream_host, caller_tag,
     * outcome}} so dashboards can compute amplification ratio and per-source
     * outbound mix. The {@code outcome} label buckets status codes into
     * coarse groups + isolates {@code 429} for the rate-limit alerting path.
     *
     * <p>Also records latency under
     * {@code pantera_upstream_request_duration_seconds} with the same labels.
     *
     * @param upstreamHost the host of the upstream server (e.g.
     *     {@code "repo1.maven.org"}). Used as the {@code upstream_host} label.
     * @param callerTag    one of {@code "foreground"}, {@code "cooldown_head"},
     *     {@code "metadata_refresh"} — identifies which Pantera subsystem
     *     issued the outbound request. Defaulted from the
     *     {@code RequestContext.KEY_CALLER_TAG} ThreadContext entry by the
     *     calling slice.
     * @param outcome      one of {@code "2xx"}, {@code "3xx"}, {@code "4xx"},
     *     {@code "429"}, {@code "5xx"}, {@code "timeout"},
     *     {@code "connect_error"}, {@code "error"}. The {@code "429"} bucket
     *     is isolated for alerting.
     * @param durationMs   wall-clock duration of the upstream call in
     *     milliseconds (request send → response received).
     */
    public void recordOutboundRequest(
        final String upstreamHost,
        final String callerTag,
        final String outcome,
        final long durationMs
    ) {
        Counter.builder("pantera.upstream.requests.total")
            .description("Outbound HTTP requests to upstream registries, "
                + "labelled by caller subsystem and outcome bucket. "
                + "Drives the amplification-ratio alert.")
            .tags(
                "upstream_host", upstreamHost,
                "caller_tag", callerTag,
                "outcome", outcome
            )
            .register(registry)
            .increment();
        Timer.builder("pantera.upstream.request.duration")
            .description("Outbound HTTP request latency to upstream registries.")
            .tags(
                "upstream_host", upstreamHost,
                "caller_tag", callerTag,
                "outcome", outcome
            )
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Record an upstream {@code 429 Too Many Requests} response. Primary
     * alerting signal for "Maven Central / npm registry / packagist /
     * etc. is rate-limiting us."
     *
     * <p>Emits {@code pantera_proxy_429_total{upstream_host, repo_name}}.
     * Operators should alert on
     * {@code sum(increase(pantera_proxy_429_total[10m])) > 0} per host
     * — any sustained 429 from a known-throttling upstream means our
     * rate limiter is set too high or our amplification ratio is above 1.
     *
     * <p>Called from {@code JettyClientSlice} in addition to
     * {@link #recordOutboundRequest} (the latter buckets the same response
     * under {@code outcome="429"} for amplification context).
     *
     * @param upstreamHost host that returned 429 (e.g. {@code "repo1.maven.org"}).
     * @param repoName     repository the foreground request was for
     *     (e.g. {@code "maven_proxy"}); read from
     *     {@code ThreadContext.get(RequestContext.KEY_REPO_NAME)} by the
     *     caller. Pass {@code "unknown"} when not available.
     */
    public void recordUpstream429(final String upstreamHost, final String repoName) {
        Counter.builder("pantera.proxy.429.total")
            .description("Upstream 429 Too Many Requests responses received, "
                + "labelled by upstream host and originating repo. Primary "
                + "throttling alert signal.")
            .tags("upstream_host", upstreamHost, "repo_name", repoName)
            .register(registry)
            .increment();
    }

    /**
     * Record a self-imposed outbound rate-limit hit. Differs from
     * {@link #recordUpstream429}: this fires when Pantera's local
     * token-bucket gate denied an outbound request before it left the
     * JVM (M3 of {@code analysis/plan/v1/PLAN.md}). A non-zero count
     * here is normal — it means the limiter is doing its job; a
     * sustained high rate means the bucket size is too small for the
     * legitimate workload.
     *
     * <p>Emits {@code pantera_outbound_rate_limited_total{upstream_host,
     * reason}}. {@code reason} ∈ {@code "gate_closed"}
     * (per-host 429 / Retry-After gate is open) or {@code "bucket_empty"}
     * (steady-state RPS cap hit, gate is open).
     *
     * @param upstreamHost host whose limit was hit.
     * @param reason {@code "gate_closed"} or {@code "bucket_empty"}.
     * @since 2.2.0
     */
    public void recordOutboundRateLimited(final String upstreamHost, final String reason) {
        Counter.builder("pantera.outbound.rate_limited.total")
            .description("Outbound requests denied by Pantera's own per-host token-bucket "
                + "or 429 gate. Sustained non-zero rate hints the bucket is undersized "
                + "for legitimate traffic.")
            .tags("upstream_host", upstreamHost, "reason", reason)
            .register(registry)
            .increment();
    }

    /**
     * Bucket a response status code into a coarse outcome label.
     * Isolates {@code 429} from the rest of {@code 4xx} for the alerting
     * path.
     *
     * @param statusCode HTTP status code from the upstream response.
     * @return one of {@code "2xx"}, {@code "3xx"}, {@code "4xx"},
     *     {@code "429"}, {@code "5xx"}, or {@code "unknown"}.
     */
    public static String outcomeBucket(final int statusCode) {
        if (statusCode == 429) {
            return "429";
        }
        if (statusCode >= 200 && statusCode < 300) {
            return "2xx";
        }
        if (statusCode >= 300 && statusCode < 400) {
            return "3xx";
        }
        if (statusCode >= 400 && statusCode < 500) {
            return "4xx";
        }
        if (statusCode >= 500 && statusCode < 600) {
            return "5xx";
        }
        return "unknown";
    }

    /**
     * Bucket an upstream-call failure into a coarse outcome label.
     *
     * @param failure the {@link Throwable} surfaced from the Jetty client.
     *     {@code null} returns {@code "error"} as a safe default.
     * @return one of {@code "timeout"}, {@code "connect_error"},
     *     {@code "error"}.
     */
    public static String outcomeFromFailure(final Throwable failure) {
        if (failure == null) {
            return "error";
        }
        if (failure instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        if (failure instanceof java.net.ConnectException
            || failure instanceof java.net.SocketException) {
            return "connect_error";
        }
        // Jetty wraps connection failures sometimes; check the cause too.
        final Throwable cause = failure.getCause();
        if (cause instanceof java.net.ConnectException
            || cause instanceof java.net.SocketException) {
            return "connect_error";
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        return "error";
    }
}

