/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

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
            .build();
        this.dependencies = Caffeine.newBuilder()
            .maximumSize(dependencyMaxSize)
            .expireAfterWrite(dependencyTtl)
            .recordStats()
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
        
        // Fast path: cached result
        final Optional<Instant> cached = this.releaseDates.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        // Deduplication: check if already fetching
        final CompletableFuture<Optional<Instant>> existing = this.inflightReleases.get(key);
        if (existing != null) {
            return existing;
        }
        
        // Fetch from delegate
        final CompletableFuture<Optional<Instant>> future = this.delegate.releaseDate(artifact, version)
            .whenComplete((result, error) -> {
                this.inflightReleases.remove(key);
                if (error == null && result != null) {
                    this.releaseDates.put(key, result);
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
        
        // Fast path: cached result
        final List<CooldownDependency> cached = this.dependencies.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        // Deduplication: check if already fetching
        final CompletableFuture<List<CooldownDependency>> existing = this.inflightDeps.get(key);
        if (existing != null) {
            return existing;
        }
        
        // Fetch from delegate
        final CompletableFuture<List<CooldownDependency>> future = this.delegate.dependencies(artifact, version)
            .whenComplete((result, error) -> {
                this.inflightDeps.remove(key);
                if (error == null && result != null) {
                    this.dependencies.put(key, result);
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
                    // Cache all batch results
                    results.forEach((dep, date) -> {
                        final String key = key(dep.artifact(), dep.version());
                        this.releaseDates.put(key, date);
                    });
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
}
