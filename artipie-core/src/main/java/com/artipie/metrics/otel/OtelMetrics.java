/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.semconv.ResourceAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenTelemetry metrics for Artipie.
 * Provides comprehensive observability across all repository types, caches, and upstreams.
 * Uses OpenTelemetry Java SDK 1.56.0
 * 
 * @since 1.18.23
 */
public final class OtelMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelMetrics.class);
    
    private static volatile OtelMetrics instance;
    
    private final OpenTelemetry openTelemetry;
    private final Meter meter;
    
    // === HTTP Request Metrics ===
    private final LongCounter httpRequests;
    private final DoubleHistogram httpDuration;
    private final DoubleHistogram httpRequestSize;
    private final DoubleHistogram httpResponseSize;
    private final AtomicLong activeRequests = new AtomicLong(0);
    
    // === Repository Operation Metrics ===
    private final LongCounter artifactDownloads;
    private final LongCounter artifactUploads;
    private final DoubleHistogram artifactSize;
    private final LongCounter metadataOperations;
    private final DoubleHistogram metadataGenDuration;
    
    // === Cache Metrics ===
    private final LongCounter cacheRequests;
    private final Map<String, AtomicLong> cacheHits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheMisses = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheSizes = new ConcurrentHashMap<>();
    private final LongCounter cacheEvictions;
    private final LongCounter cacheErrors;
    private final DoubleHistogram cacheDuration;
    private final LongCounter cacheDeduplications;
    
    // === Proxy & Upstream Metrics ===
    private final LongCounter proxyRequests;
    private final DoubleHistogram proxyDuration;
    private final Map<String, AtomicLong> upstreamAvailability = new ConcurrentHashMap<>();
    private final LongCounter upstreamErrors;
    private final Map<String, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();
    
    // === Group Metrics ===
    private final LongCounter groupRequests;
    private final LongCounter groupMemberRequests;
    private final DoubleHistogram groupResolutionDuration;
    
    // === Storage Metrics ===
    private final LongCounter storageOperations;
    private final DoubleHistogram storageDuration;
    private final Map<String, AtomicLong> storageSizes = new ConcurrentHashMap<>();
    
    // Common attribute keys
    private static final AttributeKey<String> REPO_NAME = AttributeKey.stringKey("repo_name");
    private static final AttributeKey<String> REPO_TYPE = AttributeKey.stringKey("repo_type");
    private static final AttributeKey<String> REPO_SUBTYPE = AttributeKey.stringKey("repo_subtype");
    private static final AttributeKey<String> CACHE_TYPE = AttributeKey.stringKey("cache_type");
    private static final AttributeKey<String> UPSTREAM = AttributeKey.stringKey("upstream");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> STATUS_CODE = AttributeKey.stringKey("status_code");
    private static final AttributeKey<String> METHOD = AttributeKey.stringKey("method");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error_type");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> MEMBER_NAME = AttributeKey.stringKey("member_name");
    private static final AttributeKey<String> CACHE_TIER = AttributeKey.stringKey("cache_tier");
    private static final AttributeKey<String> ERROR_REASON = AttributeKey.stringKey("error_reason");
    
    private OtelMetrics() {
        this.openTelemetry = initializeOpenTelemetry();
        this.meter = openTelemetry.getMeter("com.artipie");
        
        // Initialize HTTP metrics
        this.httpRequests = meter.counterBuilder("artipie.http.requests")
            .setDescription("Total HTTP requests")
            .setUnit("1")
            .build();
        
        this.httpDuration = meter.histogramBuilder("artipie.http.request.duration")
            .setDescription("HTTP request duration")
            .setUnit("ms")
            .build();
        
        this.httpRequestSize = meter.histogramBuilder("artipie.http.request.size.bytes")
            .setDescription("HTTP request body size")
            .setUnit("By")
            .build();
        
        this.httpResponseSize = meter.histogramBuilder("artipie.http.response.size.bytes")
            .setDescription("HTTP response body size")
            .setUnit("By")
            .build();
        
        meter.gaugeBuilder("artipie.http.active.requests")
            .setDescription("Currently active HTTP requests")
            .buildWithCallback(measurement -> measurement.record(activeRequests.get()));
        
        // Initialize repository operation metrics
        this.artifactDownloads = meter.counterBuilder("artipie.artifact.downloads")
            .setDescription("Artifact download count")
            .setUnit("1")
            .build();
        
        this.artifactUploads = meter.counterBuilder("artipie.artifact.uploads")
            .setDescription("Artifact upload count")
            .setUnit("1")
            .build();
        
        this.artifactSize = meter.histogramBuilder("artipie.artifact.size.bytes")
            .setDescription("Artifact size distribution")
            .setUnit("By")
            .build();
        
        this.metadataOperations = meter.counterBuilder("artipie.metadata.operations")
            .setDescription("Metadata operations count")
            .setUnit("1")
            .build();
        
        this.metadataGenDuration = meter.histogramBuilder("artipie.metadata.generation.duration")
            .setDescription("Metadata generation duration")
            .setUnit("ms")
            .build();
        
        // Initialize cache metrics
        this.cacheRequests = meter.counterBuilder("artipie.cache.requests")
            .setDescription("Cache requests (hit/miss)")
            .setUnit("1")
            .build();
        
        this.cacheEvictions = meter.counterBuilder("artipie.cache.evictions")
            .setDescription("Cache evictions")
            .setUnit("1")
            .build();
        
        this.cacheErrors = meter.counterBuilder("artipie.cache.errors")
            .setDescription("Cache errors (L2: timeouts, connection issues)")
            .setUnit("1")
            .build();
        
        this.cacheDuration = meter.histogramBuilder("artipie.cache.operation.duration")
            .setDescription("Cache operation latency")
            .setUnit("ms")
            .build();
        
        this.cacheDeduplications = meter.counterBuilder("artipie.cache.deduplications")
            .setDescription("Deduplicated cache requests")
            .setUnit("1")
            .build();
        
        // Initialize proxy metrics
        this.proxyRequests = meter.counterBuilder("artipie.proxy.requests")
            .setDescription("Proxy upstream requests")
            .setUnit("1")
            .build();
        
        this.proxyDuration = meter.histogramBuilder("artipie.proxy.request.duration")
            .setDescription("Proxy upstream request duration")
            .setUnit("ms")
            .build();
        
        this.upstreamErrors = meter.counterBuilder("artipie.upstream.errors")
            .setDescription("Upstream errors")
            .setUnit("1")
            .build();
        
        // Initialize group metrics
        this.groupRequests = meter.counterBuilder("artipie.group.requests")
            .setDescription("Group repository requests")
            .setUnit("1")
            .build();
        
        this.groupMemberRequests = meter.counterBuilder("artipie.group.member.requests")
            .setDescription("Requests to group members")
            .setUnit("1")
            .build();
        
        this.groupResolutionDuration = meter.histogramBuilder("artipie.group.resolution.duration")
            .setDescription("Time to resolve artifact in group")
            .setUnit("ms")
            .build();
        
        // Initialize storage metrics
        this.storageOperations = meter.counterBuilder("artipie.storage.operations")
            .setDescription("Storage operations")
            .setUnit("1")
            .build();
        
        this.storageDuration = meter.histogramBuilder("artipie.storage.operation.duration")
            .setDescription("Storage operation duration")
            .setUnit("ms")
            .build();
        
        LOGGER.info("OpenTelemetry metrics initialized successfully");
    }
    
    private OpenTelemetry initializeOpenTelemetry() {
        final String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "artipie");
        final String serviceVersion = System.getenv().getOrDefault("OTEL_SERVICE_VERSION", "1.18.22");
        final String environment = System.getenv().getOrDefault("OTEL_ENVIRONMENT", "production");
        
        final Resource resource = Resource.getDefault().toBuilder()
            .put(ResourceAttributes.SERVICE_NAME, serviceName)
            .put(ResourceAttributes.SERVICE_VERSION, serviceVersion)
            .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, environment)
            .build();
        
        // Metrics are exported via Elastic APM Java Agent automatically
        // We just need to provide the SDK, agent handles OTLP export
        final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .build();
        
        return OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .buildAndRegisterGlobal();
    }
    
    public static synchronized void initialize() {
        if (instance == null) {
            instance = new OtelMetrics();
        }
    }
    
    public static OtelMetrics get() {
        if (instance == null) {
            throw new IllegalStateException("OtelMetrics not initialized");
        }
        return instance;
    }
    
    public static boolean isInitialized() {
        return instance != null;
    }
    
    // === HTTP Metrics Methods ===
    
    public void recordHttpRequest(String repoName, String repoType, String repoSubtype, 
                                  String method, int statusCode, double durationMs) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            REPO_TYPE, repoType,
            REPO_SUBTYPE, repoSubtype,
            METHOD, method,
            STATUS_CODE, String.valueOf(statusCode)
        );
        httpRequests.add(1, attrs);
        httpDuration.record(durationMs, attrs);
    }
    
    public ActiveRequest startHttpRequest() {
        activeRequests.incrementAndGet();
        return () -> activeRequests.decrementAndGet();
    }
    
    // === Cache Metrics Methods ===
    
    public void recordCacheHit(String repoName, String repoType, String cacheType) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            REPO_TYPE, repoType,
            CACHE_TYPE, cacheType,
            RESULT, "hit"
        );
        cacheRequests.add(1, attrs);
        
        final String key = repoName + ":" + cacheType;
        cacheHits.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    public void recordCacheMiss(String repoName, String repoType, String cacheType) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            REPO_TYPE, repoType,
            CACHE_TYPE, cacheType,
            RESULT, "miss"
        );
        cacheRequests.add(1, attrs);
        
        final String key = repoName + ":" + cacheType;
        cacheMisses.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    public void recordCacheEviction(String repoName, String cacheType, String reason) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            CACHE_TYPE, cacheType,
            AttributeKey.stringKey("reason"), reason
        );
        cacheEvictions.add(1, attrs);
    }
    
    // === Proxy & Upstream Methods ===
    
    public void recordProxyRequest(String repoName, String upstream, String result, double durationMs) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            UPSTREAM, upstream,
            RESULT, result
        );
        proxyRequests.add(1, attrs);
        proxyDuration.record(durationMs, attrs);
    }
    
    public void recordUpstreamError(String repoName, String upstream, String errorType) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            UPSTREAM, upstream,
            ERROR_TYPE, errorType
        );
        upstreamErrors.add(1, attrs);
        
        final String key = repoName + ":" + upstream;
        consecutiveFailures.computeIfAbsent(key, k -> {
            final AtomicLong value = new AtomicLong(0);
            meter.gaugeBuilder("artipie.upstream.consecutive.failures")
                .setDescription("Consecutive upstream failures")
                .buildWithCallback(m -> m.record(value.get(), attrs));
            return value;
        }).incrementAndGet();
        
        setUpstreamAvailability(repoName, upstream, false);
    }
    
    public void recordUpstreamSuccess(String repoName, String upstream) {
        final String key = repoName + ":" + upstream;
        final AtomicLong failures = consecutiveFailures.get(key);
        if (failures != null) {
            failures.set(0);
        }
        setUpstreamAvailability(repoName, upstream, true);
    }
    
    private void setUpstreamAvailability(String repoName, String upstream, boolean available) {
        final String key = repoName + ":" + upstream;
        upstreamAvailability.computeIfAbsent(key, k -> {
            final AtomicLong value = new AtomicLong(available ? 1 : 0);
            final Attributes attrs = Attributes.of(REPO_NAME, repoName, UPSTREAM, upstream);
            meter.gaugeBuilder("artipie.upstream.availability")
                .setDescription("Upstream availability (1=up, 0=down)")
                .buildWithCallback(m -> m.record(value.get(), attrs));
            return value;
        }).set(available ? 1 : 0);
    }
    
    // === Group Metrics Methods ===
    
    public void recordGroupRequest(String repoName) {
        groupRequests.add(1, Attributes.of(REPO_NAME, repoName));
    }
    
    public void recordGroupMemberRequest(String repoName, String memberName, String result) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            MEMBER_NAME, memberName,
            RESULT, result
        );
        groupMemberRequests.add(1, attrs);
    }
    
    public void recordGroupResolution(String repoName, double durationMs) {
        groupResolutionDuration.record(durationMs, Attributes.of(REPO_NAME, repoName));
    }
    
    // === Artifact Methods ===
    
    public void recordDownload(String repoName, String repoType, String repoSubtype, long sizeBytes) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            REPO_TYPE, repoType,
            REPO_SUBTYPE, repoSubtype
        );
        artifactDownloads.add(1, attrs);
        artifactSize.record(sizeBytes, Attributes.of(REPO_TYPE, repoType, OPERATION, "download"));
    }
    
    public void recordUpload(String repoName, String repoType, String repoSubtype, long sizeBytes) {
        final Attributes attrs = Attributes.of(
            REPO_NAME, repoName,
            REPO_TYPE, repoType,
            REPO_SUBTYPE, repoSubtype
        );
        artifactUploads.add(1, attrs);
        artifactSize.record(sizeBytes, Attributes.of(REPO_TYPE, repoType, OPERATION, "upload"));
    }
    
    public interface ActiveRequest extends AutoCloseable {
        @Override
        void close();
    }
    
    // === Two-Tier Cache Methods ===
    
    /**
     * Record L1 cache hit.
     * @param cacheName Cache name (cooldown, negative, auth, etc.)
     * @param durationMs Operation duration in milliseconds
     */
    public void recordL1Hit(final String cacheName, final double durationMs) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, "l1",
            RESULT, "hit"
        );
        cacheRequests.add(1, attrs);
        cacheDuration.record(durationMs, attrs);
        
        final String key = cacheName + ":l1";
        cacheHits.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Record L1 cache miss.
     * @param cacheName Cache name
     * @param durationMs Operation duration in milliseconds
     */
    public void recordL1Miss(final String cacheName, final double durationMs) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, "l1",
            RESULT, "miss"
        );
        cacheRequests.add(1, attrs);
        cacheDuration.record(durationMs, attrs);
        
        final String key = cacheName + ":l1";
        cacheMisses.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Record L2 cache hit.
     * @param cacheName Cache name
     * @param durationMs Operation duration in milliseconds
     */
    public void recordL2Hit(final String cacheName, final double durationMs) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, "l2",
            RESULT, "hit"
        );
        cacheRequests.add(1, attrs);
        cacheDuration.record(durationMs, attrs);
        
        final String key = cacheName + ":l2";
        cacheHits.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Record L2 cache miss.
     * @param cacheName Cache name
     * @param durationMs Operation duration in milliseconds
     */
    public void recordL2Miss(final String cacheName, final double durationMs) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, "l2",
            RESULT, "miss"
        );
        cacheRequests.add(1, attrs);
        cacheDuration.record(durationMs, attrs);
        
        final String key = cacheName + ":l2";
        cacheMisses.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Record L2 cache error.
     * @param cacheName Cache name
     * @param errorType Error type (timeout, connection_refused, serialization, etc.)
     * @param durationMs Operation duration before error
     */
    public void recordL2Error(final String cacheName, final String errorType, final double durationMs) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, "l2",
            ERROR_REASON, errorType
        );
        cacheErrors.add(1, attrs);
        cacheDuration.record(durationMs, attrs);
    }
    
    /**
     * Record deduplication event (for CooldownCache).
     * @param cacheName Cache name
     */
    public void recordCacheDeduplication(final String cacheName) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            OPERATION, "deduplication"
        );
        cacheDeduplications.add(1, attrs);
    }
    
    /**
     * Register cache size gauge.
     * @param cacheName Cache name
     * @param tier Cache tier (l1 or l2)
     * @param sizeSupplier Supplier for current size
     */
    public void registerCacheSize(final String cacheName, final String tier, 
                                   final java.util.function.Supplier<Long> sizeSupplier) {
        final Attributes attrs = Attributes.of(
            CACHE_TYPE, cacheName,
            CACHE_TIER, tier
        );
        
        meter.gaugeBuilder("artipie.cache.size.entries")
            .setDescription("Current cache size in entries")
            .buildWithCallback(measurement -> {
                try {
                    final long size = sizeSupplier.get();
                    measurement.record(size, attrs);
                } catch (Exception e) {
                    LOGGER.debug("Failed to get cache size for {}:{}", cacheName, tier, e);
                }
            });
    }
}
