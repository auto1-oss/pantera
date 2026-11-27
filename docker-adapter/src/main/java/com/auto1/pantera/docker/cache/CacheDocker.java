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
     * Whether a blob of the origin is stored in the cache while it is read.
     */
    private final boolean store;

    /**
     * When a manifest of the origin is copied into the cache.
     */
    private final ManifestCopies copies;

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
        this(
            origin, cache, events, inspector, upstreamUrl, new ConcurrentHashMap<>(), true,
            ManifestCopies.AT_ONCE
        );
    }

    /**
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact metadata events queue
     * @param inspector Cooldown inspector
     * @param upstreamUrl Upstream URL for metrics
     * @param inflight Manifest cache copies in flight for this proxy repository
     * @param store Whether a blob of the origin is stored in the cache while
     *  it is read
     * @param copies When a manifest of the origin is copied into the cache
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CacheDocker(Docker origin,
                        Docker cache,
                        Optional<Queue<ArtifactEvent>> events,
                        Optional<DockerProxyCooldownInspector> inspector,
                        String upstreamUrl,
                        ConcurrentMap<String, CompletableFuture<Void>> inflight,
                        boolean store,
                        ManifestCopies copies
    ) {
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
        this.inflight = inflight;
        this.store = store;
        this.copies = copies;
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
            this.store,
            this.copies
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
            this.upstreamUrl, this.inflight, false, ManifestCopies.NEVER
        );
    }

    /**
     * The same repository for a client's request on which cooldown decides
     * after the manifest was read: a manifest the origin returns is copied
     * into the cache (stored, its artifact event queued) as {@code decided}
     * says. For a GET, only when the answer serves it,
     * {@link ManifestCopies#commit()}, never when cooldown refuses it,
     * {@link ManifestCopies#discard()}; for a HEAD, whose answer is never
     * refused, only when the {@link ManifestCopies.Verdict} allows it.
     * Everything else is as in this view: blobs are stored while they are
     * read, release dates are recorded, and concurrent copies of one
     * reference are made once.
     *
     * @param decided Copies of the manifests the request reads
     * @return View of this repository for one request
     */
    public Docker deciding(final ManifestCopies decided) {
        return new CacheDocker(
            this.origin, this.cache, this.events, this.inspector,
            this.upstreamUrl, this.inflight, true, decided
        );
    }
}
