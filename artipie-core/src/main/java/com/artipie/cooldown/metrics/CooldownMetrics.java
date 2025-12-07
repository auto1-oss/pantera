/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metrics;

import com.artipie.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Metrics for cooldown functionality.
 * Provides comprehensive observability for cooldown operations.
 *
 * <p>Metric naming convention: {@code artipie.cooldown.*}</p>
 *
 * <p>Metrics emitted:</p>
 * <ul>
 *   <li><b>Counters:</b></li>
 *   <ul>
 *     <li>{@code artipie.cooldown.versions.blocked} - versions blocked count</li>
 *     <li>{@code artipie.cooldown.versions.allowed} - versions allowed count</li>
 *     <li>{@code artipie.cooldown.cache.hits} - cache hits (L1/L2)</li>
 *     <li>{@code artipie.cooldown.cache.misses} - cache misses</li>
 *     <li>{@code artipie.cooldown.all_blocked} - all versions blocked events</li>
 *     <li>{@code artipie.cooldown.invalidations} - cache invalidations</li>
 *   </ul>
 *   <li><b>Gauges:</b></li>
 *   <ul>
 *     <li>{@code artipie.cooldown.cache.size} - current cache size</li>
 *     <li>{@code artipie.cooldown.active_blocks} - active blocks count</li>
 *   </ul>
 *   <li><b>Timers:</b></li>
 *   <ul>
 *     <li>{@code artipie.cooldown.metadata.filter.duration} - metadata filtering duration</li>
 *     <li>{@code artipie.cooldown.evaluate.duration} - per-version evaluation duration</li>
 *     <li>{@code artipie.cooldown.cache.load.duration} - cache load duration</li>
 *   </ul>
 * </ul>
 *
 * @since 1.0
 */
public final class CooldownMetrics {

    /**
     * Singleton instance.
     */
    private static volatile CooldownMetrics instance;

    /**
     * Meter registry.
     */
    private final MeterRegistry registry;

    /**
     * Active blocks gauge values per repo.
     */
    private final Map<String, AtomicLong> activeBlocksGauges;

    /**
     * Cache size supplier (set by FilteredMetadataCache).
     */
    private volatile Supplier<Long> cacheSizeSupplier;

    /**
     * Private constructor.
     *
     * @param registry Meter registry
     */
    private CooldownMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.activeBlocksGauges = new ConcurrentHashMap<>();
        this.cacheSizeSupplier = () -> 0L;

        // Register cache size gauge
        Gauge.builder("artipie.cooldown.cache.size", this, m -> m.cacheSizeSupplier.get().doubleValue())
            .description("Current cooldown metadata cache size")
            .register(registry);
    }

    /**
     * Get or create singleton instance.
     *
     * @return CooldownMetrics instance, or null if MicrometerMetrics not initialized
     */
    public static CooldownMetrics getInstance() {
        if (instance == null) {
            synchronized (CooldownMetrics.class) {
                if (instance == null && MicrometerMetrics.isInitialized()) {
                    instance = new CooldownMetrics(MicrometerMetrics.getInstance().getRegistry());
                }
            }
        }
        return instance;
    }

    /**
     * Check if metrics are available.
     *
     * @return true if metrics can be recorded
     */
    public static boolean isAvailable() {
        return getInstance() != null;
    }

    /**
     * Set cache size supplier for gauge.
     *
     * @param supplier Supplier returning current cache size
     */
    public void setCacheSizeSupplier(final Supplier<Long> supplier) {
        this.cacheSizeSupplier = supplier;
    }

    // ==================== Counters ====================

    /**
     * Record blocked version.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void recordVersionBlocked(final String repoType, final String repoName) {
        Counter.builder("artipie.cooldown.versions.blocked")
            .description("Number of versions blocked by cooldown")
            .tag("repo_type", repoType)
            .tag("repo_name", repoName)
            .register(this.registry)
            .increment();
    }

    /**
     * Record allowed version.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void recordVersionAllowed(final String repoType, final String repoName) {
        Counter.builder("artipie.cooldown.versions.allowed")
            .description("Number of versions allowed by cooldown")
            .tag("repo_type", repoType)
            .tag("repo_name", repoName)
            .register(this.registry)
            .increment();
    }

    /**
     * Record cache hit.
     *
     * @param tier Cache tier (l1 or l2)
     */
    public void recordCacheHit(final String tier) {
        Counter.builder("artipie.cooldown.cache.hits")
            .description("Cooldown cache hits")
            .tag("tier", tier)
            .register(this.registry)
            .increment();
    }

    /**
     * Record cache miss.
     */
    public void recordCacheMiss() {
        Counter.builder("artipie.cooldown.cache.misses")
            .description("Cooldown cache misses")
            .register(this.registry)
            .increment();
    }

    /**
     * Record all versions blocked event.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     */
    public void recordAllVersionsBlocked(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        Counter.builder("artipie.cooldown.all_blocked")
            .description("Events where all versions of a package were blocked")
            .tag("repo_type", repoType)
            .tag("repo_name", repoName)
            .register(this.registry)
            .increment();
    }

    /**
     * Record cache invalidation.
     *
     * @param repoType Repository type
     * @param reason Invalidation reason (unblock, expire, manual)
     */
    public void recordInvalidation(final String repoType, final String reason) {
        Counter.builder("artipie.cooldown.invalidations")
            .description("Cooldown cache invalidations")
            .tag("repo_type", repoType)
            .tag("reason", reason)
            .register(this.registry)
            .increment();
    }

    // ==================== Gauges ====================

    /**
     * Update active blocks count for a repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param count Current active blocks count
     */
    public void updateActiveBlocks(final String repoType, final String repoName, final long count) {
        final String key = repoType + ":" + repoName;
        this.activeBlocksGauges.computeIfAbsent(key, k -> {
            final AtomicLong gauge = new AtomicLong(0);
            Gauge.builder("artipie.cooldown.active_blocks", gauge, AtomicLong::doubleValue)
                .description("Number of active cooldown blocks")
                .tag("repo_type", repoType)
                .tag("repo_name", repoName)
                .register(this.registry);
            return gauge;
        }).set(count);
    }

    // ==================== Timers ====================

    /**
     * Record metadata filtering duration.
     *
     * @param repoType Repository type
     * @param durationMs Duration in milliseconds
     * @param versionsTotal Total versions in metadata
     * @param versionsBlocked Number of blocked versions
     */
    public void recordFilterDuration(
        final String repoType,
        final long durationMs,
        final int versionsTotal,
        final int versionsBlocked
    ) {
        Timer.builder("artipie.cooldown.metadata.filter.duration")
            .description("Cooldown metadata filtering duration")
            .tag("repo_type", repoType)
            .tag("versions_bucket", versionsBucket(versionsTotal))
            .register(this.registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record per-version evaluation duration.
     *
     * @param repoType Repository type
     * @param durationMs Duration in milliseconds
     * @param blocked Whether version was blocked
     */
    public void recordEvaluateDuration(
        final String repoType,
        final long durationMs,
        final boolean blocked
    ) {
        Timer.builder("artipie.cooldown.evaluate.duration")
            .description("Per-version cooldown evaluation duration")
            .tag("repo_type", repoType)
            .tag("result", blocked ? "blocked" : "allowed")
            .register(this.registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record cache load duration.
     *
     * @param durationMs Duration in milliseconds
     */
    public void recordCacheLoadDuration(final long durationMs) {
        Timer.builder("artipie.cooldown.cache.load.duration")
            .description("Cooldown cache load duration on miss")
            .register(this.registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Get versions bucket for histogram.
     */
    private static String versionsBucket(final int versions) {
        if (versions <= 10) {
            return "1-10";
        } else if (versions <= 50) {
            return "11-50";
        } else if (versions <= 200) {
            return "51-200";
        } else if (versions <= 500) {
            return "201-500";
        } else {
            return "500+";
        }
    }
}
