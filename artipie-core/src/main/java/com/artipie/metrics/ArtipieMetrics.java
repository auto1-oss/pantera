/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import com.artipie.http.log.EcsLogger;

/**
 * Artipie metrics - Compatibility wrapper for OpenTelemetry.
 * Delegates all calls to OtelMetrics for backward compatibility.
 * 
 * @deprecated Use {@link com.artipie.metrics.otel.OtelMetrics} directly
 * @since 1.18.20
 */
@Deprecated
public final class ArtipieMetrics {

    private static volatile ArtipieMetrics instance;
    
    private ArtipieMetrics() {
        // Private constructor
    }
    
    /**
     * Initialize (no-op, OtelMetrics handles initialization).
     * @param registry Ignored (for compatibility)
     */
    public static void initialize(final Object registry) {
        if (instance == null) {
            synchronized (ArtipieMetrics.class) {
                if (instance == null) {
                    instance = new ArtipieMetrics();
                    EcsLogger.info("com.artipie.metrics")
                        .message("ArtipieMetrics compatibility wrapper initialized (delegate: OtelMetrics)")
                        .eventCategory("metrics")
                        .eventAction("metrics_init")
                        .eventOutcome("success")
                        .log();
                }
            }
        }
    }
    
    public static ArtipieMetrics instance() {
        if (instance == null) {
            throw new IllegalStateException("ArtipieMetrics not initialized");
        }
        return instance;
    }
    
    public static boolean isEnabled() {
        return com.artipie.metrics.otel.OtelMetrics.isInitialized();
    }
    
    // === Delegating Methods ===
    
    public void cacheHit(final String repoType) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordCacheHit(
                repoType, repoType, "artifact"
            );
        }
    }
    
    public void cacheMiss(final String repoType) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordCacheMiss(
                repoType, repoType, "artifact"
            );
        }
    }
    
    public double getCacheHitRate(final String repoType) {
        // Computed in Elastic APM from metrics
        return 0.0;
    }
    
    public void download(final String repoType) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordDownload(
                repoType, repoType, "proxy", 0
            );
        }
    }
    
    public void upload(final String repoType) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordUpload(
                repoType, repoType, "local", 0
            );
        }
    }
    
    public ActiveOperation startUpload() {
        // Not tracked in OpenTelemetry version
        return () -> {};
    }
    
    public ActiveOperation startDownload() {
        // Not tracked in OpenTelemetry version
        return () -> {};
    }
    
    public void bandwidth(final String repoType, final String direction, final long bytes) {
        // Bandwidth is calculated from artifact size in OpenTelemetry version
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            if ("download".equals(direction)) {
                com.artipie.metrics.otel.OtelMetrics.get().recordDownload(
                    repoType, repoType, "proxy", bytes
                );
            } else if ("upload".equals(direction)) {
                com.artipie.metrics.otel.OtelMetrics.get().recordUpload(
                    repoType, repoType, "local", bytes
                );
            }
        }
    }
    
    public Object startMetadataGeneration(final String repoType) {
        // Return dummy sample (OpenTelemetry tracks differently)
        return null;
    }
    
    public void stopMetadataGeneration(final String repoType, final Object sample) {
        // No-op in OpenTelemetry version
    }
    
    public void updateStorageUsage(final long usedBytes, final long quotaBytes) {
        // Storage metrics tracked separately in OpenTelemetry
    }
    
    public void addStorageUsage(final long bytes) {
        // Storage metrics tracked separately in OpenTelemetry
    }
    
    public void updateUpstreamAvailability(final String upstream, final boolean available) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            if (available) {
                com.artipie.metrics.otel.OtelMetrics.get().recordUpstreamSuccess(upstream, upstream);
            } else {
                com.artipie.metrics.otel.OtelMetrics.get().recordUpstreamError(upstream, upstream, "unavailable");
            }
        }
    }
    
    public void upstreamFailure(final String upstream, final String errorType) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordUpstreamError(upstream, upstream, errorType);
        }
    }
    
    public void upstreamSuccess(final String upstream) {
        if (com.artipie.metrics.otel.OtelMetrics.isInitialized()) {
            com.artipie.metrics.otel.OtelMetrics.get().recordUpstreamSuccess(upstream, upstream);
        }
    }
    
    /**
     * Active operation tracker.
     */
    public interface ActiveOperation extends AutoCloseable {
        @Override
        void close();
    }
}
