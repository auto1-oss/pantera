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
package com.auto1.pantera.asto.blob;

/**
 * Process-wide static holder for the REAL {@link CachedBlobStorageMetrics}
 * implementation (built over {@code pantera-core}'s {@code
 * MicrometerMetrics}), mirroring {@link StorageInvalidationBusRegistry}
 * (WS1.5) exactly -- see that class's javadoc for why a static registry,
 * not a constructor argument, is the seam: {@code
 * CachedBlobStorage}-constructing factories live below {@code pantera-core}
 * and cannot depend on it.
 *
 * <p>{@link CachedBlobStorage}'s constructor reads {@link #active()} ONCE,
 * at construction, exactly like it reads {@code
 * StorageInvalidationBusRegistry.active()} via its caller -- {@code
 * pantera-main} MUST install the real implementation before
 * {@code RepositorySlices} builds any {@code CachedBlobStorage} (same
 * ordering requirement WS1.5 documents) or that instance keeps {@link
 * CachedBlobStorageMetrics#NOOP} for its entire lifetime.</p>
 *
 * @since 2.3.0
 */
public final class CachedBlobStorageMetricsRegistry {

    /**
     * Installed metrics implementation, or {@code null} if none.
     */
    private static volatile CachedBlobStorageMetrics installed;

    private CachedBlobStorageMetricsRegistry() {
    }

    /**
     * Install the process-wide metrics implementation. Idempotent -- a later
     * call replaces an earlier one.
     *
     * @param metrics Real implementation.
     */
    public static void install(final CachedBlobStorageMetrics metrics) {
        CachedBlobStorageMetricsRegistry.installed = metrics;
    }

    /**
     * Clear the installed implementation (tests, shutdown).
     */
    public static void uninstall() {
        CachedBlobStorageMetricsRegistry.installed = null;
    }

    /**
     * The active implementation: the installed one, or {@link
     * CachedBlobStorageMetrics#NOOP} if none was installed. Safe to call
     * before {@link #install}.
     *
     * @return Active implementation, never {@code null}.
     */
    public static CachedBlobStorageMetrics active() {
        final CachedBlobStorageMetrics current = CachedBlobStorageMetricsRegistry.installed;
        return current == null ? CachedBlobStorageMetrics.NOOP : current;
    }
}
