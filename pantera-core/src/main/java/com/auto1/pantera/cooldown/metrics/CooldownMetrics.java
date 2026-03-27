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
package com.auto1.pantera.cooldown.metrics;

import com.auto1.pantera.metrics.MicrometerMetrics;
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
 * <p>Metric naming convention: {@code pantera.cooldown.*}</p>
 *
 * <p>Metrics emitted:</p>
 * <ul>
 *   <li><b>Counters:</b></li>
 *   <ul>
 *     <li>{@code pantera.cooldown.versions.blocked} - versions blocked count</li>
 *     <li>{@code pantera.cooldown.versions.allowed} - versions allowed count</li>
 *     <li>{@code pantera.cooldown.cache.hits} - cache hits (L1/L2)</li>
 *     <li>{@code pantera.cooldown.cache.misses} - cache misses</li>
 *     <li>{@code pantera.cooldown.all_blocked} - all versions blocked events</li>
 *     <li>{@code pantera.cooldown.invalidations} - cache invalidations</li>
 *   </ul>
 *   <li><b>Gauges:</b></li>
 *   <ul>
 *     <li>{@code pantera.cooldown.cache.size} - current cache size</li>
 *     <li>{@code pantera.cooldown.active_blocks} - active blocks count</li>
 *   </ul>
 *   <li><b>Timers:</b></li>
 *   <ul>
 *     <li>{@code pantera.cooldown.metadata.filter.duration} - metadata filtering duration</li>
 *     <li>{@code pantera.cooldown.evaluate.duration} - per-version evaluation duration</li>
 *     <li>{@code pantera.cooldown.cache.load.duration} - cache load duration</li>
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
     * All-blocked packages count (packages where ALL versions are blocked).
     */
    private final AtomicLong allBlockedPackages;

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
        this.allBlockedPackages = new AtomicLong(0);
        this.cacheSizeSupplier = () -> 0L;

        // Register cache size gauge - uses 'this' reference to prevent GC
        Gauge.builder("pantera.cooldown.cache.size", this, m -> m.cacheSizeSupplier.get().doubleValue())
            .description("Current cooldown metadata cache size")
            .register(registry);

        // Register global active_blocks gauge - always emits, computes total from per-repo gauges
        // Uses 'this' reference to prevent GC and ensure gauge is never stale
        Gauge.builder("pantera.cooldown.active_blocks", this, CooldownMetrics::computeTotalActiveBlocks)
            .description("Total active cooldown blocks across all repositories")
            .register(registry);

        // Register all_blocked gauge - tracks packages where ALL versions are blocked
        // Persisted in database, loaded on startup, always emits current value
        Gauge.builder("pantera.cooldown.all_blocked", this.allBlockedPackages, AtomicLong::doubleValue)
            .description("Number of packages where all versions are blocked")
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
        Counter.builder("pantera.cooldown.versions.blocked")
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
        Counter.builder("pantera.cooldown.versions.allowed")
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
        Counter.builder("pantera.cooldown.cache.hits")
            .description("Cooldown cache hits")
            .tag("tier", tier)
            .register(this.registry)
            .increment();
    }

    /**
     * Record cache miss.
     */
    public void recordCacheMiss() {
        Counter.builder("pantera.cooldown.cache.misses")
            .description("Cooldown cache misses")
            .register(this.registry)
            .increment();
    }

    /**
     * Set all-blocked packages count (loaded from database on startup).
     *
     * @param count Current count of packages with all versions blocked
     */
    public void setAllBlockedPackages(final long count) {
        this.allBlockedPackages.set(count);
    }

    /**
     * Increment all-blocked packages count (called when all versions become blocked).
     */
    public void incrementAllBlocked() {
        this.allBlockedPackages.incrementAndGet();
    }

    /**
     * Decrement all-blocked packages count (called when a package is unblocked).
     */
    public void decrementAllBlocked() {
        final long current = this.allBlockedPackages.decrementAndGet();
        if (current < 0) {
            this.allBlockedPackages.set(0);
        }
    }

    /**
     * Record cache invalidation.
     *
     * @param repoType Repository type
     * @param reason Invalidation reason (unblock, expire, manual)
     */
    public void recordInvalidation(final String repoType, final String reason) {
        Counter.builder("pantera.cooldown.invalidations")
            .description("Cooldown cache invalidations")
            .tag("repo_type", repoType)
            .tag("reason", reason)
            .register(this.registry)
            .increment();
    }

    // ==================== Gauges ====================

    /**
     * Update active blocks count for a repository (used on startup to load from DB).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param count Current active blocks count
     */
    public void updateActiveBlocks(final String repoType, final String repoName, final long count) {
        final String key = repoType + ":" + repoName;
        this.getOrCreateRepoGauge(repoType, repoName, key).set(count);
    }

    /**
     * Increment active blocks for a repository (O(1), no DB query).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void incrementActiveBlocks(final String repoType, final String repoName) {
        final String key = repoType + ":" + repoName;
        this.getOrCreateRepoGauge(repoType, repoName, key).incrementAndGet();
    }

    /**
     * Decrement active blocks for a repository (O(1), no DB query).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public void decrementActiveBlocks(final String repoType, final String repoName) {
        final String key = repoType + ":" + repoName;
        final AtomicLong gauge = this.activeBlocksGauges.get(key);
        if (gauge != null) {
            final long val = gauge.decrementAndGet();
            if (val < 0) {
                gauge.set(0);
            }
        }
    }

    /**
     * Get or create per-repo gauge.
     */
    private AtomicLong getOrCreateRepoGauge(final String repoType, final String repoName, final String key) {
        return this.activeBlocksGauges.computeIfAbsent(key, k -> {
            final AtomicLong newGauge = new AtomicLong(0);
            Gauge.builder("pantera.cooldown.active_blocks.repo", newGauge, AtomicLong::doubleValue)
                .description("Number of active cooldown blocks per repository")
                .tag("repo_type", repoType)
                .tag("repo_name", repoName)
                .register(this.registry);
            return newGauge;
        });
    }

    /**
     * Compute total active blocks across all repositories.
     * Called by Micrometer on each scrape - ensures gauge always emits.
     *
     * @return Total active blocks count
     */
    private double computeTotalActiveBlocks() {
        return this.activeBlocksGauges.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
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
        Timer.builder("pantera.cooldown.metadata.filter.duration")
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
        Timer.builder("pantera.cooldown.evaluate.duration")
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
        Timer.builder("pantera.cooldown.cache.load.duration")
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
