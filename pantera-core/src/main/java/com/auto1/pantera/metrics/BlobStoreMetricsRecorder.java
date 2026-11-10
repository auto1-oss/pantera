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

import com.auto1.pantera.asto.metrics.BlobStoreMetricsCollector;

/**
 * Bridges {@code MeteredBlobStore}'s (pantera-storage-core) {@link
 * BlobStoreMetricsCollector} calls to {@link MicrometerMetrics} (WS1.6,
 * spec {@code WS1-storage-for-scale.md} &sect;3.G) -- the exact same
 * bridge shape {@link StorageMetricsRecorder} already uses for {@code
 * FileStorage}.
 *
 * @since 2.3.0
 */
public final class BlobStoreMetricsRecorder implements BlobStoreMetricsCollector.Recorder {

    /**
     * Singleton instance.
     */
    private static final BlobStoreMetricsRecorder INSTANCE = new BlobStoreMetricsRecorder();

    private BlobStoreMetricsRecorder() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     *
     * @return Singleton instance.
     */
    public static BlobStoreMetricsRecorder getInstance() {
        return INSTANCE;
    }

    /**
     * Install this recorder into {@link BlobStoreMetricsCollector}. Call
     * during application startup, after {@link MicrometerMetrics#initialize}.
     */
    public static void initialize() {
        BlobStoreMetricsCollector.setRecorder(INSTANCE);
    }

    @Override
    public void recordOperation(
        final String backend, final String operation, final String outcome, final long durationNs
    ) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordBlobStoreOperation(
                backend, operation, outcome, durationNs / 1_000_000
            );
        }
    }
}
