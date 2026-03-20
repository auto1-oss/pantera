/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;

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
        this.name = name;
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.repoName = registryName;
        this.inspector = inspector;
        this.upstreamUrl = upstreamUrl;
    }

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
            this.upstreamUrl
        );
    }

    @Override
    public Uploads uploads() {
        throw new UnsupportedOperationException();
    }
}
