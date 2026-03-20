/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.docker.Catalog;
import com.artipie.docker.Docker;
import com.artipie.docker.Repo;
import com.artipie.docker.misc.JoinedCatalogSource;
import com.artipie.docker.misc.Pagination;
import com.artipie.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

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
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
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
            this.upstreamUrl
        );
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        return new JoinedCatalogSource(pagination, this.origin, this.cache).catalog();
    }
}
