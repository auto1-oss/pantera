/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for cooldown service to expose to Prometheus/Micrometer.
 *
 * @since 1.0
 */
public final class CooldownMetrics {

    /**
     * Total requests evaluated.
     */
    private final AtomicLong totalRequests = new AtomicLong(0);

    /**
     * Total requests blocked.
     */
    private final AtomicLong totalBlocked = new AtomicLong(0);

    /**
     * Total requests allowed.
     */
    private final AtomicLong totalAllowed = new AtomicLong(0);

    /**
     * Total requests auto-allowed due to circuit breaker.
     */
    private final AtomicLong circuitBreakerAutoAllowed = new AtomicLong(0);

    /**
     * Total cache hits.
     */
    private final AtomicLong cacheHits = new AtomicLong(0);

    /**
     * Total cache misses.
     */
    private final AtomicLong cacheMisses = new AtomicLong(0);

    /**
     * Blocked count per repository type.
     * Key: repoType
     */
    private final ConcurrentMap<String, AtomicLong> blockedByRepoType = new ConcurrentHashMap<>();

    /**
     * Blocked count per repository.
     * Key: repoType:repoName
     */
    private final ConcurrentMap<String, AtomicLong> blockedByRepo = new ConcurrentHashMap<>();

    /**
     * Record a cooldown evaluation.
     *
     * @param result Evaluation result
     */
    public void recordEvaluation(final CooldownResult result) {
        this.totalRequests.incrementAndGet();
        if (result.blocked()) {
            this.totalBlocked.incrementAndGet();
        } else {
            this.totalAllowed.incrementAndGet();
        }
    }

    /**
     * Record a block by repository.
     *
     * @param repoType Repository type (maven, npm, etc.)
     * @param repoName Repository name
     */
    public void recordBlock(final String repoType, final String repoName) {
        this.blockedByRepoType.computeIfAbsent(repoType, k -> new AtomicLong(0)).incrementAndGet();
        final String repoKey = repoType + ":" + repoName;
        this.blockedByRepo.computeIfAbsent(repoKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Record circuit breaker auto-allow.
     */
    public void recordCircuitBreakerAutoAllow() {
        this.circuitBreakerAutoAllowed.incrementAndGet();
    }

    /**
     * Record cache hit.
     */
    public void recordCacheHit() {
        this.cacheHits.incrementAndGet();
    }

    /**
     * Record cache miss.
     */
    public void recordCacheMiss() {
        this.cacheMisses.incrementAndGet();
    }

    /**
     * Get total requests evaluated.
     *
     * @return Total requests
     */
    public long getTotalRequests() {
        return this.totalRequests.get();
    }

    /**
     * Get total requests blocked.
     *
     * @return Total blocked
     */
    public long getTotalBlocked() {
        return this.totalBlocked.get();
    }

    /**
     * Get total requests allowed.
     *
     * @return Total allowed
     */
    public long getTotalAllowed() {
        return this.totalAllowed.get();
    }

    /**
     * Get circuit breaker auto-allowed count.
     *
     * @return Auto-allowed count
     */
    public long getCircuitBreakerAutoAllowed() {
        return this.circuitBreakerAutoAllowed.get();
    }

    /**
     * Get cache hits.
     *
     * @return Cache hits
     */
    public long getCacheHits() {
        return this.cacheHits.get();
    }

    /**
     * Get cache misses.
     *
     * @return Cache misses
     */
    public long getCacheMisses() {
        return this.cacheMisses.get();
    }

    /**
     * Get cache hit rate as percentage.
     *
     * @return Hit rate (0-100)
     */
    public double getCacheHitRate() {
        final long total = this.cacheHits.get() + this.cacheMisses.get();
        return total == 0 ? 0.0 : (double) this.cacheHits.get() / total * 100.0;
    }

    /**
     * Get blocked count for repository type.
     *
     * @param repoType Repository type
     * @return Blocked count
     */
    public long getBlockedByRepoType(final String repoType) {
        final AtomicLong counter = this.blockedByRepoType.get(repoType);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Get blocked count for specific repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Blocked count
     */
    public long getBlockedByRepo(final String repoType, final String repoName) {
        final String repoKey = repoType + ":" + repoName;
        final AtomicLong counter = this.blockedByRepo.get(repoKey);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Get all repository types with blocks.
     *
     * @return Repository type names
     */
    public java.util.Set<String> getRepoTypes() {
        return this.blockedByRepoType.keySet();
    }

    /**
     * Get all repositories with blocks.
     *
     * @return Repository keys (repoType:repoName)
     */
    public java.util.Set<String> getRepos() {
        return this.blockedByRepo.keySet();
    }

    /**
     * Get metrics summary.
     *
     * @return Metrics string
     */
    public String summary() {
        return String.format(
            "CooldownMetrics[total=%d, blocked=%d (%.1f%%), allowed=%d, cacheHitRate=%.1f%%, circuitBreakerAutoAllowed=%d]",
            this.totalRequests.get(),
            this.totalBlocked.get(),
            this.totalRequests.get() == 0 ? 0.0 : (double) this.totalBlocked.get() / this.totalRequests.get() * 100.0,
            this.totalAllowed.get(),
            getCacheHitRate(),
            this.circuitBreakerAutoAllowed.get()
        );
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        this.totalRequests.set(0);
        this.totalBlocked.set(0);
        this.totalAllowed.set(0);
        this.circuitBreakerAutoAllowed.set(0);
        this.cacheHits.set(0);
        this.cacheMisses.set(0);
        this.blockedByRepoType.clear();
        this.blockedByRepo.clear();
    }
}
