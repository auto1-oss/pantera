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

import com.auto1.pantera.asto.metrics.StorageMetricsCollector;

/**
 * Storage metrics recorder implementation that bridges StorageMetricsCollector
 * to MicrometerMetrics.
 *
 * This class implements the MetricsRecorder interface from asto-core and
 * delegates to MicrometerMetrics for actual metrics collection.
 *
 * @since 1.20.0
 */
public final class StorageMetricsRecorder implements StorageMetricsCollector.MetricsRecorder {

    /**
     * Singleton instance.
     */
    private static final StorageMetricsRecorder INSTANCE = new StorageMetricsRecorder();

    /**
     * Private constructor for singleton.
     */
    private StorageMetricsRecorder() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     * @return Singleton instance
     */
    public static StorageMetricsRecorder getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize storage metrics recording.
     * Call this during application startup after MicrometerMetrics is initialized.
     */
    public static void initialize() {
        StorageMetricsCollector.setRecorder(INSTANCE);
    }

    @Override
    public void recordOperation(final String operation, final long durationNs,
                               final boolean success, final String storageId) {
        if (MicrometerMetrics.isInitialized()) {
            final long durationMs = durationNs / 1_000_000;
            final String result = success ? "success" : "failure";
            MicrometerMetrics.getInstance().recordStorageOperation(
                operation,
                result,
                durationMs
            );
        }
    }

    @Override
    public void recordOperation(final String operation, final long durationNs,
                               final boolean success, final String storageId,
                               final long sizeBytes) {
        // For now, just record the operation (size is not used in MicrometerMetrics.recordStorageOperation)
        recordOperation(operation, durationNs, success, storageId);
    }
}

