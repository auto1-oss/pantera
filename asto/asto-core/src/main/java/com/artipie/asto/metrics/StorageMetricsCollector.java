/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.metrics;

/**
 * Storage metrics collector stub.
 * This is a placeholder for future metrics collection functionality.
 * 
 * @since 1.20.0
 */
public final class StorageMetricsCollector {

    /**
     * Private constructor to prevent instantiation.
     */
    private StorageMetricsCollector() {
        // Utility class
    }

    /**
     * Record a storage operation metric (without size tracking).
     * Currently a no-op stub.
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
        // No-op stub - metrics collection not yet implemented
    }

    /**
     * Record a storage operation metric (with size tracking).
     * Currently a no-op stub.
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
        // No-op stub - metrics collection not yet implemented
    }
}

