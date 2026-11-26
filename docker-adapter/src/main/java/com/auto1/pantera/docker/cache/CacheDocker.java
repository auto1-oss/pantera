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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.JoinedCatalogSource;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache {@link Docker} implementation.
 *
 * @since 0.3
 */
public final class CacheDocker implements Docker {

    /**
     * Origin repository.
     */
    private final Docker origin;

    /**
     * Cache repository.
     */
    private final Docker cache;

    /**
     * Artifact metadata events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Cooldown inspector to access per-request metadata.
     */
    private final Optional<DockerProxyCooldownInspector> inspector;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Whether what the origin returns is stored in the cache.
     */
    private final boolean store;

    /**
     * Manifest cache copies in flight for this proxy repository: concurrent
     * first pulls of one tag store and publish it once.
     */
    private final ConcurrentMap<String, CompletableFuture<Void>> inflight;

    /**
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events queue
     * @param inspector Cooldown inspector
     */
    public CacheDocker(Docker origin,
                       Docker cache,
                       Optional<Queue<ArtifactEvent>> events,
                       Optional<DockerProxyCooldownInspector> inspector
    ) {
        this(origin, cache, events, inspector, "unknown");
    }

    /**
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events queue
     * @param inspector Cooldown inspector
     * @param upstreamUrl Upstream URL for metrics
     */
    public CacheDocker(Docker origin,
                       Docker cache,
                       Optional<Queue<ArtifactEvent>> events,
                       Optional<DockerProxyCooldownInspector> inspector,
                       String upstreamUrl
    ) {
        this(origin, cache, events, inspector, upstreamUrl, true);
    }

    /**
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events queue
     * @param inspector Cooldown inspector
     * @param upstreamUrl Upstream URL for metrics
     * @param store Whether what the origin returns is stored in the cache
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CacheDocker(Docker origin,
                        Docker cache,
                        Optional<Queue<ArtifactEvent>> events,
                        Optional<DockerProxyCooldownInspector> inspector,
                        String upstreamUrl,
                        boolean store
    ) {
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
        this.store = store;
        this.inflight = new ConcurrentHashMap<>();
    }

    @Override
    public String registryName() {
        return origin.registryName();
    }

    @Override
    public Repo repo(final String name) {
        return new CacheRepo(
            name,
            this.origin.repo(name),
            this.cache.repo(name),
            this.events,
            registryName(),
            this.inspector,
            this.upstreamUrl,
            this.inflight,
            this.store
        );
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        return new JoinedCatalogSource(pagination, this.origin, this.cache).catalog();
    }

    /**
     * The same repository for a request that must leave it as it found it:
     * a Troubleshoot replay ({@code RequestTrace}) asks what a client would
     * get. This view reads the cache and the origin in the same order and
     * returns the same manifest or blob, but stores nothing in the cache,
     * queues no artifact event and records no release date. The body of a
     * blob the cache does not hold is the origin's own.
     *
     * @return Read-only view of this repository
     */
    public Docker readOnly() {
        return new CacheDocker(
            this.origin, this.cache, Optional.empty(), Optional.empty(),
            this.upstreamUrl, false
        );
    }
}
