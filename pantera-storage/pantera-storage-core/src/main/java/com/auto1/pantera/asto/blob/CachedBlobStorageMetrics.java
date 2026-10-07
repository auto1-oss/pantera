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
 * Dependency-inversion seam for {@link CachedBlobStorage}'s cache-tier
 * metrics (WS1.6, spec {@code WS1-storage-for-scale.md} &sect;3.G): disk
 * hit/miss ratio, eviction bytes, and cross-node invalidation apply/ignore
 * outcomes -- exactly the same architectural shape as {@link
 * StorageInvalidationBus} (WS1.5), since {@link CachedBlobStorage} sits
 * BELOW {@code pantera-core}'s {@code MicrometerMetrics} and cannot call it
 * directly.
 *
 * <p>{@link #bind(CachedBlobStorage)} is the gauge-registration half: called
 * once, from {@link CachedBlobStorage}'s constructor, so the real
 * implementation (installed above this module, in {@code pantera-core}) can
 * register polling gauges reading {@code storage}'s public observability
 * accessors ({@link CachedBlobStorage#diskBytesUsed()}, {@link
 * CachedBlobStorage#maxDiskBytes()}, {@link
 * CachedBlobStorage#writeBackQueueDepth()}, {@link
 * CachedBlobStorage#writeBackQueueCapacity()}, {@link
 * CachedBlobStorage#oldestPendingWriteAgeMillis()}) tagged by {@link
 * CachedBlobStorage#cacheId()} -- see that method's javadoc for why a
 * per-cache tag, not a per-repo one, is correct here (a single {@code
 * CachedBlobStorage} instance can serve more than one repository via a
 * shared storage alias).</p>
 *
 * @since 2.3.0
 */
public interface CachedBlobStorageMetrics {

    /**
     * No-op metrics: the default until a higher module installs a real
     * implementation via {@link CachedBlobStorageMetricsRegistry#install}
     * (e.g. metrics disabled entirely, or a boot that never reaches that
     * install call).
     */
    CachedBlobStorageMetrics NOOP = new CachedBlobStorageMetrics() {
        @Override
        public void bind(final CachedBlobStorage storage) {
            // no-op: nothing to register
        }

        @Override
        public void recordDiskHit() {
            // no-op
        }

        @Override
        public void recordDiskMiss() {
            // no-op
        }

        @Override
        public void recordEvictionBytes(final long bytes) {
            // no-op
        }

        @Override
        public void recordInvalidationApplied() {
            // no-op
        }

        @Override
        public void recordInvalidationIgnored(final String reason) {
            // no-op
        }
    };

    /**
     * Register this instance's gauges. Called exactly once, from {@link
     * CachedBlobStorage}'s constructor. Implementations MUST be idempotent
     * per {@link CachedBlobStorage#cacheId()} (a shared storage alias could
     * in principle be bound more than once across the process lifetime).
     *
     * @param storage Newly constructed instance to bind gauges to.
     */
    void bind(CachedBlobStorage storage);

    /**
     * Record a {@code value()} call served entirely from the local disk
     * tier (zero blob-store contact).
     */
    void recordDiskHit();

    /**
     * Record a {@code value()} call that missed the disk tier and triggered
     * a cold fill from the blob store.
     */
    void recordDiskMiss();

    /**
     * Record bytes freed by one WS1.4 eviction (the "eviction bytes/sec"
     * metric deferred by WS1.4 -- a rate is a query-time {@code rate()} over
     * this monotonic counter, not a metric of its own).
     *
     * @param bytes Size of the evicted entry, in bytes.
     */
    void recordEvictionBytes(long bytes);

    /**
     * Record a WS1.5 cross-node invalidation that this node applied
     * (dropped its local disk+index entry).
     */
    void recordInvalidationApplied();

    /**
     * Record a WS1.5 cross-node invalidation this node received but did NOT
     * apply.
     *
     * @param reason Bounded reason: {@code "pending_write_in_flight"},
     *  {@code "superseded_by_local_write"}, or {@code "not_cached"} (this
     *  node never cached the key the message was about).
     */
    void recordInvalidationIgnored(String reason);
}
