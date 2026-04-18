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
package com.auto1.pantera.cooldown.impl;

import com.auto1.pantera.cache.CacheConfig;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caching wrapper for CooldownInspector implementations.
 * Prevents redundant HTTP requests to upstream registries.
 *
 * @since 1.0
 */
public final class CachedCooldownInspector implements CooldownInspector {

    /**
     * Underlying inspector.
     */
    private final CooldownInspector delegate;

    /**
     * Release date cache.
     */
    private final Cache<String, Optional<Instant>> releaseDates;

    /**
     * Dependency cache.
     */
    private final Cache<String, List<CooldownDependency>> dependencies;

    /**
     * In-flight release date requests to prevent duplicate concurrent fetches.
     */
    private final ConcurrentMap<String, CompletableFuture<Optional<Instant>>> inflightReleases;

    /**
     * In-flight dependency requests to prevent duplicate concurrent fetches.
     */
    private final ConcurrentMap<String, CompletableFuture<List<CooldownDependency>>> inflightDeps;

    /**
     * Constructor with default cache settings.
     * - Release dates: 50,000 entries, 30 minute TTL
     * - Dependencies: 10,000 entries, 30 minute TTL
     *
     * @param delegate Underlying inspector
     */
    public CachedCooldownInspector(final CooldownInspector delegate) {
        this(delegate, 50_000, Duration.ofMinutes(30), 10_000, Duration.ofMinutes(30));
    }

    /**
     * Constructor with CacheConfig support.
     * Uses configured TTL and size settings.
     *
     * @param delegate Underlying inspector
     * @param config Cache configuration from "cooldown" cache section
     */
    public CachedCooldownInspector(final CooldownInspector delegate, final CacheConfig config) {
        this(
            delegate,
            config.valkeyEnabled() ? config.l1MaxSize() : config.maxSize(),
            config.valkeyEnabled() ? config.l1Ttl() : config.ttl(),
            config.valkeyEnabled() ? config.l1MaxSize() / 5 : config.maxSize() / 5,
            config.valkeyEnabled() ? config.l1Ttl() : config.ttl()
        );
    }

    /**
     * Constructor with custom cache settings.
     *
     * @param delegate Underlying inspector
     * @param releaseDateMaxSize Maximum release date cache size
     * @param releaseDateTtl Release date cache TTL
     * @param dependencyMaxSize Maximum dependency cache size
     * @param dependencyTtl Dependency cache TTL
     */
    public CachedCooldownInspector(
        final CooldownInspector delegate,
        final long releaseDateMaxSize,
        final Duration releaseDateTtl,
        final long dependencyMaxSize,
        final Duration dependencyTtl
    ) {
        this.delegate = delegate;
        this.releaseDates = Caffeine.newBuilder()
            .maximumSize(releaseDateMaxSize)
            .expireAfterWrite(releaseDateTtl)
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
        this.dependencies = Caffeine.newBuilder()
            .maximumSize(dependencyMaxSize)
            .expireAfterWrite(dependencyTtl)
            .recordStats()
            .evictionListener(this::onEviction)
            .build();
        this.inflightReleases = new ConcurrentHashMap<>();
        this.inflightDeps = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        final String key = key(artifact, version);

        // Fast path: cached result with metrics
        final long getStartNanos = System.nanoTime();
        final Optional<Instant> cached = this.releaseDates.getIfPresent(key);
        final long getDurationMs = (System.nanoTime() - getStartNanos) / 1_000_000;

        if (cached != null) {
            // Cache HIT
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheHit("cooldown_inspector", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheOperationDuration("cooldown_inspector", "l1", "get", getDurationMs);
            }
            return CompletableFuture.completedFuture(cached);
        }

        // Cache MISS
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheMiss("cooldown_inspector", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("cooldown_inspector", "l1", "get", getDurationMs);
        }

        // Deduplication: check if already fetching
        final CompletableFuture<Optional<Instant>> existing = this.inflightReleases.get(key);
        if (existing != null) {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheDeduplication("cooldown_inspector", "l1");
            }
            return existing;
        }

        // Fetch from delegate
        final CompletableFuture<Optional<Instant>> future = this.delegate.releaseDate(artifact, version)
            .whenComplete((result, error) -> {
                this.inflightReleases.remove(key);
                if (error == null && result != null) {
                    final long putStartNanos = System.nanoTime();
                    this.releaseDates.put(key, result);
                    final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;

                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("cooldown_inspector", "l1", "put", putDurationMs);
                    }
                }
            });

        this.inflightReleases.put(key, future);
        return future;
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        final String key = key(artifact, version);

        // Fast path: cached result with metrics
        final long getStartNanos = System.nanoTime();
        final List<CooldownDependency> cached = this.dependencies.getIfPresent(key);
        final long getDurationMs = (System.nanoTime() - getStartNanos) / 1_000_000;

        if (cached != null) {
            // Cache HIT
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheHit("cooldown_inspector", "l1");
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheOperationDuration("cooldown_inspector", "l1", "get", getDurationMs);
            }
            return CompletableFuture.completedFuture(cached);
        }

        // Cache MISS
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheMiss("cooldown_inspector", "l1");
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheOperationDuration("cooldown_inspector", "l1", "get", getDurationMs);
        }

        // Deduplication: check if already fetching
        final CompletableFuture<List<CooldownDependency>> existing = this.inflightDeps.get(key);
        if (existing != null) {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordCacheDeduplication("cooldown_inspector", "l1");
            }
            return existing;
        }

        // Fetch from delegate
        final CompletableFuture<List<CooldownDependency>> future = this.delegate.dependencies(artifact, version)
            .whenComplete((result, error) -> {
                this.inflightDeps.remove(key);
                if (error == null && result != null) {
                    final long putStartNanos = System.nanoTime();
                    this.dependencies.put(key, result);
                    final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;

                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("cooldown_inspector", "l1", "put", putDurationMs);
                    }
                }
            });

        this.inflightDeps.put(key, future);
        return future;
    }

    @Override
    public CompletableFuture<Map<CooldownDependency, Optional<Instant>>> releaseDatesBatch(
        final Collection<CooldownDependency> deps
    ) {
        // Use delegate's batch implementation if available
        return this.delegate.releaseDatesBatch(deps)
            .whenComplete((results, error) -> {
                if (error == null && results != null) {
                    // Cache all batch results with metrics
                    final long putStartNanos = System.nanoTime();
                    results.forEach((dep, date) -> {
                        final String key = key(dep.artifact(), dep.version());
                        this.releaseDates.put(key, date);
                    });
                    final long putDurationMs = (System.nanoTime() - putStartNanos) / 1_000_000;

                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .recordCacheOperationDuration("cooldown_inspector", "l1", "put", putDurationMs);
                    }
                }
            });
    }

    /**
     * Get cache statistics.
     *
     * @return Statistics string
     */
    public String stats() {
        return String.format(
            "CachedInspector[releases=%d, deps=%d]",
            this.releaseDates.estimatedSize(),
            this.dependencies.estimatedSize()
        );
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        this.releaseDates.invalidateAll();
        this.dependencies.invalidateAll();
        this.inflightReleases.clear();
        this.inflightDeps.clear();
    }

    private static String key(final String artifact, final String version) {
        return artifact + ":" + version;
    }

    /**
     * Handle cache eviction - record metrics.
     * This listener is used by both releaseDates and dependencies caches.
     * @param key Cache key
     * @param value Cached value
     * @param cause Eviction cause
     */
    private void onEviction(
        final String key,
        final Object value,
        final com.github.benmanes.caffeine.cache.RemovalCause cause
    ) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordCacheEviction("cooldown_inspector", "l1", cause.toString().toLowerCase());
        }
    }
}
