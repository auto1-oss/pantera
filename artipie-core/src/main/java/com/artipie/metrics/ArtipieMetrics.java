/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Artipie custom business metrics.
 * Tracks cache efficiency, downloads, uploads, bandwidth, and storage usage.
 * Metrics are sent to Elastic APM via Micrometer.
 * 
 * @since 1.18.20
 */
public final class ArtipieMetrics {

    /**
     * Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtipieMetrics.class);

    /**
     * Singleton instance.
     */
    private static volatile ArtipieMetrics instance;

    /**
     * Meter registry (from APM).
     */
    private final MeterRegistry registry;

    /**
     * Cache hit counters by repo type.
     */
    private final ConcurrentHashMap<String, Counter> cacheHits;

    /**
     * Cache miss counters by repo type.
     */
    private final ConcurrentHashMap<String, Counter> cacheMisses;

    /**
     * Download counters by repo type.
     */
    private final ConcurrentHashMap<String, Counter> downloads;

    /**
     * Upload counters by repo type.
     */
    private final ConcurrentHashMap<String, Counter> uploads;

    /**
     * Bandwidth counters (upload/download) by repo type.
     */
    private final ConcurrentHashMap<String, Counter> bandwidth;

    /**
     * Metadata generation timers by repo type.
     */
    private final ConcurrentHashMap<String, Timer> metadataTimers;

    /**
     * Active uploads gauge.
     */
    private final AtomicLong activeUploads;

    /**
     * Active downloads gauge.
     */
    private final AtomicLong activeDownloads;

    /**
     * Storage quota usage (bytes).
     */
    private final AtomicLong storageUsedBytes;

    /**
     * Storage quota limit (bytes).
     */
    private final AtomicLong storageQuotaBytes;

    /**
     * Upstream availability by host.
     */
    private final ConcurrentHashMap<String, AtomicLong> upstreamAvailability;

    /**
     * Private constructor.
     * 
     * @param registry Meter registry
     */
    private ArtipieMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.cacheHits = new ConcurrentHashMap<>();
        this.cacheMisses = new ConcurrentHashMap<>();
        this.downloads = new ConcurrentHashMap<>();
        this.uploads = new ConcurrentHashMap<>();
        this.bandwidth = new ConcurrentHashMap<>();
        this.metadataTimers = new ConcurrentHashMap<>();
        this.activeUploads = new AtomicLong(0);
        this.activeDownloads = new AtomicLong(0);
        this.storageUsedBytes = new AtomicLong(0);
        this.storageQuotaBytes = new AtomicLong(0);
        this.upstreamAvailability = new ConcurrentHashMap<>();

        // Register gauges
        this.registerGauges();
        
        LOGGER.info("Artipie custom metrics initialized");
    }

    /**
     * Initialize metrics with registry.
     * 
     * @param registry Meter registry from APM
     */
    public static void initialize(final MeterRegistry registry) {
        if (instance == null) {
            synchronized (ArtipieMetrics.class) {
                if (instance == null) {
                    instance = new ArtipieMetrics(registry);
                }
            }
        }
    }

    /**
     * Get singleton instance.
     * 
     * @return Metrics instance
     */
    public static ArtipieMetrics instance() {
        if (instance == null) {
            throw new IllegalStateException(
                "ArtipieMetrics not initialized. Call initialize() first."
            );
        }
        return instance;
    }

    /**
     * Check if metrics are enabled.
     * 
     * @return True if initialized
     */
    public static boolean isEnabled() {
        return instance != null;
    }

    /**
     * Register gauge metrics.
     */
    private void registerGauges() {
        // Active uploads
        Gauge.builder("artipie.uploads.active", this.activeUploads, AtomicLong::get)
            .description("Number of active uploads")
            .register(this.registry);

        // Active downloads
        Gauge.builder("artipie.downloads.active", this.activeDownloads, AtomicLong::get)
            .description("Number of active downloads")
            .register(this.registry);

        // Storage quota usage
        Gauge.builder("artipie.storage.used.bytes", this.storageUsedBytes, AtomicLong::get)
            .description("Storage used in bytes")
            .register(this.registry);

        Gauge.builder("artipie.storage.quota.bytes", this.storageQuotaBytes, AtomicLong::get)
            .description("Storage quota limit in bytes")
            .register(this.registry);

        // Storage quota percentage
        Gauge.builder("artipie.storage.quota.percent", this,
            metrics -> {
                final long quota = metrics.storageQuotaBytes.get();
                if (quota == 0) {
                    return 0.0;
                }
                return (metrics.storageUsedBytes.get() * 100.0) / quota;
            }
        )
        .description("Storage quota usage percentage")
        .register(this.registry);
    }

    // ========== Cache Metrics ==========

    /**
     * Record cache hit.
     * 
     * @param repoType Repository type (npm, maven, docker, etc.)
     */
    public void cacheHit(final String repoType) {
        this.cacheHits.computeIfAbsent(repoType, type ->
            Counter.builder("artipie.cache.hits")
                .tag("repo_type", type)
                .description("Cache hits by repository type")
                .register(this.registry)
        ).increment();
    }

    /**
     * Record cache miss.
     * 
     * @param repoType Repository type
     */
    public void cacheMiss(final String repoType) {
        this.cacheMisses.computeIfAbsent(repoType, type ->
            Counter.builder("artipie.cache.misses")
                .tag("repo_type", type)
                .description("Cache misses by repository type")
                .register(this.registry)
        ).increment();
    }

    /**
     * Get cache hit rate for repo type.
     * 
     * @param repoType Repository type
     * @return Hit rate (0.0 to 1.0)
     */
    public double getCacheHitRate(final String repoType) {
        final double hits = this.cacheHits.getOrDefault(repoType, 
            Counter.builder("dummy").register(this.registry)).count();
        final double misses = this.cacheMisses.getOrDefault(repoType,
            Counter.builder("dummy").register(this.registry)).count();
        final double total = hits + misses;
        return total == 0 ? 0.0 : hits / total;
    }

    // ========== Download/Upload Metrics ==========

    /**
     * Record package download.
     * 
     * @param repoType Repository type
     */
    public void download(final String repoType) {
        this.downloads.computeIfAbsent(repoType, type ->
            Counter.builder("artipie.downloads")
                .tag("repo_type", type)
                .description("Package downloads by repository type")
                .register(this.registry)
        ).increment();
    }

    /**
     * Record package upload.
     * 
     * @param repoType Repository type
     */
    public void upload(final String repoType) {
        this.uploads.computeIfAbsent(repoType, type ->
            Counter.builder("artipie.uploads")
                .tag("repo_type", type)
                .description("Package uploads by repository type")
                .register(this.registry)
        ).increment();
    }

    /**
     * Start tracking active upload.
     * 
     * @return Upload tracker to close when done
     */
    public ActiveOperation startUpload() {
        this.activeUploads.incrementAndGet();
        return () -> this.activeUploads.decrementAndGet();
    }

    /**
     * Start tracking active download.
     * 
     * @return Download tracker to close when done
     */
    public ActiveOperation startDownload() {
        this.activeDownloads.incrementAndGet();
        return () -> this.activeDownloads.decrementAndGet();
    }

    // ========== Bandwidth Metrics ==========

    /**
     * Record bandwidth usage.
     * 
     * @param repoType Repository type
     * @param direction "upload" or "download"
     * @param bytes Number of bytes transferred
     */
    public void bandwidth(final String repoType, final String direction, final long bytes) {
        final String key = repoType + ":" + direction;
        this.bandwidth.computeIfAbsent(key, k ->
            Counter.builder("artipie.bandwidth.bytes")
                .tag("repo_type", repoType)
                .tag("direction", direction)
                .description("Bandwidth usage in bytes")
                .register(this.registry)
        ).increment(bytes);
    }

    // ========== Metadata Metrics ==========

    /**
     * Time metadata generation operation.
     * 
     * @param repoType Repository type
     * @return Timer sample to stop when done
     */
    public Timer.Sample startMetadataGeneration(final String repoType) {
        this.metadataTimers.computeIfAbsent(repoType, type ->
            Timer.builder("artipie.metadata.generation.duration")
                .tag("repo_type", type)
                .description("Metadata generation duration")
                .register(this.registry)
        );
        return Timer.start(this.registry);
    }

    /**
     * Stop metadata generation timer.
     * 
     * @param repoType Repository type
     * @param sample Timer sample from start
     */
    public void stopMetadataGeneration(final String repoType, final Timer.Sample sample) {
        final Timer timer = this.metadataTimers.get(repoType);
        if (timer != null && sample != null) {
            sample.stop(timer);
        }
    }

    // ========== Storage Metrics ==========

    /**
     * Update storage usage.
     * 
     * @param usedBytes Bytes used
     * @param quotaBytes Quota limit
     */
    public void updateStorageUsage(final long usedBytes, final long quotaBytes) {
        this.storageUsedBytes.set(usedBytes);
        this.storageQuotaBytes.set(quotaBytes);
    }

    /**
     * Add to storage usage.
     * 
     * @param bytes Bytes to add
     */
    public void addStorageUsage(final long bytes) {
        this.storageUsedBytes.addAndGet(bytes);
    }

    // ========== Upstream Availability Metrics ==========

    /**
     * Update upstream availability.
     * 
     * @param upstream Upstream host (e.g., "registry.npmjs.org")
     * @param available True if available, false if down
     */
    public void updateUpstreamAvailability(final String upstream, final boolean available) {
        final AtomicLong gauge = this.upstreamAvailability.computeIfAbsent(upstream, host -> {
            final AtomicLong value = new AtomicLong(available ? 1 : 0);
            Gauge.builder("artipie.upstream.available", value, AtomicLong::get)
                .tag("upstream", host)
                .description("Upstream availability (1=up, 0=down)")
                .register(this.registry);
            return value;
        });
        gauge.set(available ? 1 : 0);
    }

    /**
     * Record upstream failure.
     * 
     * @param upstream Upstream host
     * @param errorType Error type (timeout, connection_refused, etc.)
     */
    public void upstreamFailure(final String upstream, final String errorType) {
        Counter.builder("artipie.upstream.failures")
            .tag("upstream", upstream)
            .tag("error_type", errorType)
            .description("Upstream request failures")
            .register(this.registry)
            .increment();
        
        // Mark as unavailable
        this.updateUpstreamAvailability(upstream, false);
    }

    /**
     * Record upstream success.
     * 
     * @param upstream Upstream host
     */
    public void upstreamSuccess(final String upstream) {
        this.updateUpstreamAvailability(upstream, true);
    }

    /**
     * Active operation tracker (AutoCloseable).
     */
    public interface ActiveOperation extends AutoCloseable {
        @Override
        void close();
    }
}
