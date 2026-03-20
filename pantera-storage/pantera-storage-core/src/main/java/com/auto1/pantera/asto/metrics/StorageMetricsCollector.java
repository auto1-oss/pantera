/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.metrics;

/**
 * Storage metrics collector interface.
 * This is a placeholder that allows storage implementations to report metrics
 * without creating a dependency on the metrics implementation.
 *
 * The actual metrics collection is implemented at the application level
 * using OtelMetrics.recordStorageOperation() methods.
 *
 * @since 1.20.0
 */
public final class StorageMetricsCollector {

    /**
     * Metrics recorder instance (optional).
     */
    private static volatile MetricsRecorder recorder = null;

    /**
     * Private constructor to prevent instantiation.
     */
    private StorageMetricsCollector() {
        // Utility class
    }

    /**
     * Set the metrics recorder implementation.
     * This should be called during application initialization.
     *
     * @param metricsRecorder Metrics recorder implementation
     */
    public static void setRecorder(final MetricsRecorder metricsRecorder) {
        recorder = metricsRecorder;
    }

    /**
     * Record a storage operation metric (without size tracking).
     *
     * @param operation Operation name (e.g., "exists", "list")
     * @param durationNs Duration in nanoseconds
     * @param success Whether the operation succeeded
     * @param storageId Storage identifier
     */
    public static void record(
        final String operation,
        final long durationNs,
        final boolean success,
        final String storageId
    ) {
        final MetricsRecorder rec = recorder;
        if (rec != null) {
            rec.recordOperation(operation, durationNs, success, storageId);
        }
    }

    /**
     * Record a storage operation metric (with size tracking).
     *
     * @param operation Operation name (e.g., "save", "value", "move")
     * @param durationNs Duration in nanoseconds
     * @param success Whether the operation succeeded
     * @param storageId Storage identifier
     * @param sizeBytes Size in bytes (for operations that involve data transfer)
     */
    public static void record(
        final String operation,
        final long durationNs,
        final boolean success,
        final String storageId,
        final long sizeBytes
    ) {
        final MetricsRecorder rec = recorder;
        if (rec != null) {
            rec.recordOperation(operation, durationNs, success, storageId, sizeBytes);
        }
    }

    /**
     * Interface for metrics recording implementation.
     * Implement this interface in pantera-core to bridge to OtelMetrics.
     */
    public interface MetricsRecorder {
        /**
         * Record operation without size.
         * @param operation Operation name
         * @param durationNs Duration in nanoseconds
         * @param success Success flag
         * @param storageId Storage identifier
         */
        void recordOperation(String operation, long durationNs, boolean success, String storageId);

        /**
         * Record operation with size.
         * @param operation Operation name
         * @param durationNs Duration in nanoseconds
         * @param success Success flag
         * @param storageId Storage identifier
         * @param sizeBytes Size in bytes
         */
        void recordOperation(String operation, long durationNs, boolean success,
                           String storageId, long sizeBytes);
    }
}

