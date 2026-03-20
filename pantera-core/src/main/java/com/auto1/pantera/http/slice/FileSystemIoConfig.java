/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.http.log.EcsLogger;

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
        EcsLogger.info("com.auto1.pantera.http")
            .message("FileSystem I/O thread pool configured with " + this.threads + " threads (" + Runtime.getRuntime().availableProcessors() + " CPU cores)")
            .eventCategory("configuration")
            .eventAction("thread_pool_init")
            .eventOutcome("success")
            .log();
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
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Invalid thread pool size in system property " + PROPERTY_THREADS + "='" + sysProp + "', using default")
                    .eventCategory("configuration")
                    .eventAction("thread_pool_config")
                    .eventOutcome("failure")
                    .log();
            }
        }

        // Try environment variable
        final String envVar = System.getenv(ENV_THREADS);
        if (envVar != null && !envVar.trim().isEmpty()) {
            try {
                final int value = Integer.parseInt(envVar.trim());
                return this.validateThreadPoolSize(value, "environment variable");
            } catch (final NumberFormatException ex) {
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Invalid thread pool size in environment variable " + ENV_THREADS + "='" + envVar + "', using default")
                    .eventCategory("configuration")
                    .eventAction("thread_pool_config")
                    .eventOutcome("failure")
                    .log();
            }
        }

        // Use default: 2x CPU cores (minimum 8)
        final int cpuCores = Runtime.getRuntime().availableProcessors();
        final int defaultSize = Math.max(8, cpuCores * 2);
        EcsLogger.debug("com.auto1.pantera.http")
            .message("Using default thread pool size of " + defaultSize + " threads (" + cpuCores + " CPU cores)")
            .eventCategory("configuration")
            .eventAction("thread_pool_config")
            .eventOutcome("success")
            .log();
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
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Thread pool size from " + source + " below minimum (requested: " + value + ", using: " + MIN_THREADS + ", min: " + MIN_THREADS + ")")
                .eventCategory("configuration")
                .eventAction("thread_pool_validate")
                .eventOutcome("success")
                .log();
            return MIN_THREADS;
        }
        if (value > MAX_THREADS) {
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Thread pool size from " + source + " exceeds maximum (requested: " + value + ", using: " + MAX_THREADS + ", max: " + MAX_THREADS + ")")
                .eventCategory("configuration")
                .eventAction("thread_pool_validate")
                .eventOutcome("success")
                .log();
            return MAX_THREADS;
        }
        EcsLogger.debug("com.auto1.pantera.http")
            .message("Thread pool size from " + source + " validated: " + value + " threads")
            .eventCategory("configuration")
            .eventAction("thread_pool_validate")
            .eventOutcome("success")
            .log();
        return value;
    }
}

