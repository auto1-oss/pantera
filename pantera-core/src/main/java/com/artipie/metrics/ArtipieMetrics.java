/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import com.artipie.http.log.EcsLogger;

/**
 * Artipie metrics - Compatibility wrapper for Micrometer.
 * Delegates all calls to MicrometerMetrics for backward compatibility.
 *
 * @deprecated Use {@link MicrometerMetrics} directly
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
        return MicrometerMetrics.isInitialized();
    }
    
    // === Delegating Methods ===
    
    public void cacheHit(final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordCacheHit(repoType, "l1");
        }
    }

    public void cacheMiss(final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordCacheMiss(repoType, "l1");
        }
    }
    
    public double getCacheHitRate(final String repoType) {
        // Computed in Elastic APM from metrics
        return 0.0;
    }
    
    public void download(final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordDownload(repoType, repoType, 0);
        }
    }

    public void download(final String repoName, final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordDownload(repoName, repoType, 0);
        }
    }

    public void upload(final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordUpload(repoType, repoType, 0);
        }
    }

    public void upload(final String repoName, final String repoType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordUpload(repoName, repoType, 0);
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
        if (MicrometerMetrics.isInitialized()) {
            if ("download".equals(direction)) {
                MicrometerMetrics.getInstance().recordDownload(repoType, repoType, bytes);
            } else if ("upload".equals(direction)) {
                MicrometerMetrics.getInstance().recordUpload(repoType, repoType, bytes);
            }
        }
    }

    public void bandwidth(final String repoName, final String repoType, final String direction, final long bytes) {
        if (MicrometerMetrics.isInitialized()) {
            if ("download".equals(direction)) {
                MicrometerMetrics.getInstance().recordDownload(repoName, repoType, bytes);
            } else if ("upload".equals(direction)) {
                MicrometerMetrics.getInstance().recordUpload(repoName, repoType, bytes);
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
    
    public void updateUpstreamAvailability(final String repoName, final String upstream, final boolean available) {
        if (MicrometerMetrics.isInitialized()) {
            if (available) {
                MicrometerMetrics.getInstance().recordUpstreamSuccess(upstream);
            } else {
                MicrometerMetrics.getInstance().recordUpstreamError(repoName, upstream, "unavailable");
            }
        }
    }

    public void upstreamFailure(final String repoName, final String upstream, final String errorType) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordUpstreamError(repoName, upstream, errorType);
        }
    }

    public void upstreamSuccess(final String upstream) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordUpstreamSuccess(upstream);
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
