/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.jcabi.log.Logger;

/**
 * Configuration for filesystem I/O thread pool.
 * 
 * <p>This class provides centralized configuration for the dedicated blocking
 * executor used by {@link FileSystemArtifactSlice} and {@link FileSystemBrowseSlice}.
 * 
 * <p>Thread pool sizing can be configured via:
 * <ul>
 *   <li>System property: {@code artipie.filesystem.io.threads}</li>
 *   <li>Environment variable: {@code ARTIPIE_FILESYSTEM_IO_THREADS}</li>
 *   <li>Default: {@code Math.max(8, Runtime.getRuntime().availableProcessors() * 2)}</li>
 * </ul>
 * 
 * <p>The thread pool size should be tuned based on:
 * <ul>
 *   <li>Storage type (local SSD, EBS, network storage)</li>
 *   <li>Provisioned IOPS and throughput</li>
 *   <li>Expected concurrent request load</li>
 *   <li>Instance EBS bandwidth limits</li>
 * </ul>
 * 
 * <p>Example configurations:
 * <ul>
 *   <li>c6in.4xlarge with EBS gp3 (16K IOPS, 1,000 MB/s): 14 threads</li>
 *   <li>c6in.8xlarge with EBS gp3 (37K IOPS, 2,000 MB/s): 32 threads</li>
 *   <li>Local NVMe SSD: 2x CPU cores (high IOPS capacity)</li>
 * </ul>
 * 
 * @since 1.19.3
 */
public final class FileSystemIoConfig {

    /**
     * System property name for thread pool size.
     */
    private static final String PROPERTY_THREADS = "artipie.filesystem.io.threads";

    /**
     * Environment variable name for thread pool size.
     */
    private static final String ENV_THREADS = "ARTIPIE_FILESYSTEM_IO_THREADS";

    /**
     * Minimum thread pool size (safety floor).
     */
    private static final int MIN_THREADS = 4;

    /**
     * Maximum thread pool size (safety ceiling to prevent resource exhaustion).
     */
    private static final int MAX_THREADS = 256;

    /**
     * Singleton instance.
     */
    private static final FileSystemIoConfig INSTANCE = new FileSystemIoConfig();

    /**
     * Configured thread pool size.
     */
    private final int threads;

    /**
     * Private constructor for singleton.
     */
    private FileSystemIoConfig() {
        this.threads = this.resolveThreadPoolSize();
        Logger.info(
            this,
            "FileSystem I/O thread pool configured: %d threads (CPU cores: %d)",
            this.threads,
            Runtime.getRuntime().availableProcessors()
        );
    }

    /**
     * Get singleton instance.
     * 
     * @return FileSystemIoConfig instance
     */
    public static FileSystemIoConfig instance() {
        return INSTANCE;
    }

    /**
     * Get configured thread pool size.
     * 
     * @return Thread pool size
     */
    public int threads() {
        return this.threads;
    }

    /**
     * Resolve thread pool size from configuration sources.
     * Priority: System property > Environment variable > Default
     * 
     * @return Resolved thread pool size
     */
    private int resolveThreadPoolSize() {
        // Try system property first
        final String sysProp = System.getProperty(PROPERTY_THREADS);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            try {
                final int value = Integer.parseInt(sysProp.trim());
                return this.validateThreadPoolSize(value, "system property");
            } catch (final NumberFormatException ex) {
                Logger.warn(
                    this,
                    "Invalid thread pool size in system property %s: %s (using default)",
                    PROPERTY_THREADS,
                    sysProp
                );
            }
        }

        // Try environment variable
        final String envVar = System.getenv(ENV_THREADS);
        if (envVar != null && !envVar.trim().isEmpty()) {
            try {
                final int value = Integer.parseInt(envVar.trim());
                return this.validateThreadPoolSize(value, "environment variable");
            } catch (final NumberFormatException ex) {
                Logger.warn(
                    this,
                    "Invalid thread pool size in environment variable %s: %s (using default)",
                    ENV_THREADS,
                    envVar
                );
            }
        }

        // Use default: 2x CPU cores (minimum 8)
        final int cpuCores = Runtime.getRuntime().availableProcessors();
        final int defaultSize = Math.max(8, cpuCores * 2);
        Logger.info(
            this,
            "Using default thread pool size: %d (2x CPU cores, minimum 8)",
            defaultSize
        );
        return defaultSize;
    }

    /**
     * Validate thread pool size is within acceptable bounds.
     * 
     * @param value Thread pool size to validate
     * @param source Configuration source (for logging)
     * @return Validated thread pool size (clamped to min/max)
     */
    private int validateThreadPoolSize(final int value, final String source) {
        if (value < MIN_THREADS) {
            Logger.warn(
                this,
                "Thread pool size from %s (%d) is below minimum (%d), using minimum",
                source,
                value,
                MIN_THREADS
            );
            return MIN_THREADS;
        }
        if (value > MAX_THREADS) {
            Logger.warn(
                this,
                "Thread pool size from %s (%d) exceeds maximum (%d), using maximum",
                source,
                value,
                MAX_THREADS
            );
            return MAX_THREADS;
        }
        Logger.info(
            this,
            "Thread pool size from %s: %d",
            source,
            value
        );
        return value;
    }
}

