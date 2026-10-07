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

import com.auto1.pantera.asto.blob.CachedBlobStorage;
import com.auto1.pantera.asto.blob.CachedBlobStorageMetrics;
import com.auto1.pantera.asto.blob.CachedBlobStorageMetricsRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges {@link CachedBlobStorage}'s (pantera-storage-core) cache-tier
 * metrics to {@link MicrometerMetrics} (WS1.6, spec {@code
 * WS1-storage-for-scale.md} &sect;3.G) -- the same dependency-inversion
 * bridge shape {@code PubSubStorageInvalidationBus} (WS1.5) uses for the
 * cross-node coherence bus.
 *
 * <p>{@link #bind(CachedBlobStorage)} registers five gauges per distinct
 * {@link CachedBlobStorage#cacheId()}, tagged {@code cache=&lt;cacheId&gt;}:
 * disk bytes used, the configured max, write-back queue depth, write-back
 * queue capacity, and write-back oldest-pending age. {@link #bound} retains
 * a STRONG reference to every bound instance -- mirroring {@link
 * MicrometerMetrics#registerCircuitBreakerStateGauge}'s and {@link
 * MicrometerMetrics#registerBulkheadPermitsGauge}'s documented reason:
 * {@link Gauge.Builder} holds its state object via a {@link
 * java.lang.ref.WeakReference}, so without a strong holder here the bound
 * {@link CachedBlobStorage} would be GC-eligible the moment its only other
 * strong reference (the repository's storage field) is itself the only
 * thing keeping it alive -- retaining it here is cheap (one repository's
 * storage lives for the process lifetime) and removes any doubt.</p>
 *
 * @since 2.3.0
 */
public final class CachedBlobStorageMetricsBinder implements CachedBlobStorageMetrics {

    /**
     * Bounded cache type used for the disk hit/miss counters -- distinct
     * from {@code "storage"} ({@code StoragesCache}'s L1 Storage-instance
     * Guava cache) and every other existing {@code cacheType} value.
     */
    private static final String CACHE_TYPE = "blob_disk";

    /**
     * Bounded cache tier -- there is only one tier in {@link
     * CachedBlobStorage}'s disk cache (unlike {@code cooldown}/{@code auth},
     * which have L1+L2).
     */
    private static final String CACHE_TIER = "disk";

    /**
     * Singleton instance.
     */
    private static final CachedBlobStorageMetricsBinder INSTANCE = new CachedBlobStorageMetricsBinder();

    /**
     * Every bound instance, keyed by {@link CachedBlobStorage#cacheId()} --
     * idempotency guard (a shared storage alias's {@link
     * CachedBlobStorage#bind} could in principle run more than once) AND
     * the strong-reference retention this class's javadoc explains.
     */
    private final Map<String, CachedBlobStorage> bound = new ConcurrentHashMap<>();

    private CachedBlobStorageMetricsBinder() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     *
     * @return Singleton instance.
     */
    public static CachedBlobStorageMetricsBinder getInstance() {
        return INSTANCE;
    }

    /**
     * Install this binder into {@link CachedBlobStorageMetricsRegistry}.
     * MUST be called before {@code RepositorySlices} builds any {@link
     * CachedBlobStorage} (same ordering requirement WS1.5's {@code
     * StorageInvalidationBusRegistry.install} documents), and after {@link
     * MicrometerMetrics#initialize}.
     */
    public static void install() {
        CachedBlobStorageMetricsRegistry.install(INSTANCE);
    }

    @Override
    public void bind(final CachedBlobStorage storage) {
        if (!MicrometerMetrics.isInitialized()) {
            return;
        }
        final String cacheId = storage.cacheId();
        if (this.bound.putIfAbsent(cacheId, storage) != null) {
            return;
        }
        final MeterRegistry registry = MicrometerMetrics.getInstance().getRegistry();
        Gauge.builder("pantera.storage.cache.disk.bytes.used", storage, CachedBlobStorage::diskBytesUsed)
            .description("Bytes currently occupied by this cache.mode:index disk tier.")
            .baseUnit("bytes")
            .tag("cache", cacheId)
            .register(registry);
        Gauge.builder("pantera.storage.cache.disk.bytes.max", storage, CachedBlobStorage::maxDiskBytes)
            .description("Configured hard bound (cache.max-disk-bytes) this disk tier is admission-controlled against.")
            .baseUnit("bytes")
            .tag("cache", cacheId)
            .register(registry);
        Gauge.builder(
                "pantera.storage.cache.writeback.queue.depth", storage,
                CachedBlobStorage::writeBackQueueDepth
            )
            .description("Current WS1.2 write-back admissions outstanding (enqueued or retrying).")
            .tag("cache", cacheId)
            .register(registry);
        Gauge.builder(
                "pantera.storage.cache.writeback.queue.capacity", storage,
                CachedBlobStorage::writeBackQueueCapacity
            )
            .description("Configured write-back queue high-water mark (cache.write-back-queue-capacity).")
            .tag("cache", cacheId)
            .register(registry);
        Gauge.builder(
                "pantera.storage.cache.writeback.oldest_pending.age.seconds", storage,
                s -> s.oldestPendingWriteAgeMillis() / 1000.0
            )
            .description("Age of the longest-outstanding PENDING_WRITE upload, in seconds; 0 if none pending.")
            .baseUnit("seconds")
            .tag("cache", cacheId)
            .register(registry);
    }

    @Override
    public void recordDiskHit() {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordCacheHit(CACHE_TYPE, CACHE_TIER);
        }
    }

    @Override
    public void recordDiskMiss() {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordCacheMiss(CACHE_TYPE, CACHE_TIER);
        }
    }

    @Override
    public void recordEvictionBytes(final long bytes) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordCacheEvictionBytes(CACHE_TYPE, CACHE_TIER, bytes);
        }
    }

    @Override
    public void recordInvalidationApplied() {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordStorageInvalidation("applied", "applied");
        }
    }

    @Override
    public void recordInvalidationIgnored(final String reason) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordStorageInvalidation("applied", "ignored_" + reason);
        }
    }
}
