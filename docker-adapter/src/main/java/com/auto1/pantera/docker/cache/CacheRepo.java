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

import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache implementation of {@link Repo}.
 */
public final class CacheRepo implements Repo {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Origin repository.
     */
    private final Repo origin;

    /**
     * Cache repository.
     */
    private final Repo cache;

    /**
     * Events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Pantera repository name.
     */
    private final String repoName;

    /**
     * Cooldown inspector.
     */
    private final Optional<DockerProxyCooldownInspector> inspector;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * @param name Repository name.
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact events.
     * @param registryName Registry name.
     */
    public CacheRepo(String name, Repo origin, Repo cache,
                     Optional<Queue<ArtifactEvent>> events, String registryName,
                     Optional<DockerProxyCooldownInspector> inspector) {
        this(name, origin, cache, events, registryName, inspector, "unknown");
    }

    /**
     * @param name Repository name.
     * @param origin Origin repository.
     * @param cache Cache repository.
     * @param events Artifact events.
     * @param registryName Registry name.
     * @param inspector Cooldown inspector.
     * @param upstreamUrl Upstream URL for metrics.
     */
    public CacheRepo(String name, Repo origin, Repo cache,
                     Optional<Queue<ArtifactEvent>> events, String registryName,
                     Optional<DockerProxyCooldownInspector> inspector, String upstreamUrl) {
        this(
            name, origin, cache, events, registryName, inspector, upstreamUrl,
            new ConcurrentHashMap<>()
        );
    }

    /**
     * @param name Repository name
     * @param origin Origin repository
     * @param cache Cache repository
     * @param events Artifact events
     * @param registryName Pantera repository name
     * @param inspector Cooldown inspector
     * @param upstreamUrl Upstream URL for metrics
     * @param inflight Manifest cache copies in flight, shared per proxy repository
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CacheRepo(String name, Repo origin, Repo cache,
                     Optional<Queue<ArtifactEvent>> events, String registryName,
                     Optional<DockerProxyCooldownInspector> inspector, String upstreamUrl,
                     ConcurrentMap<String, CompletableFuture<Void>> inflight) {
        this.name = name;
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.repoName = registryName;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
        this.inflight = inflight;
    }

    /**
     * Manifest cache copies in flight, shared per proxy repository.
     */
    private final ConcurrentMap<String, CompletableFuture<Void>> inflight;

    @Override
    public Layers layers() {
        return new CacheLayers(this.origin.layers(), this.cache.layers(), this.repoName, this.upstreamUrl);
    }

    @Override
    public Manifests manifests() {
        return new CacheManifests(
            this.name,
            this.origin,
            this.cache,
            this.events,
            this.repoName,
            this.inspector,
            this.upstreamUrl,
            this.inflight
        );
    }

    @Override
    public Uploads uploads() {
        throw new UnsupportedOperationException();
    }
}
