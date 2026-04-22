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
package com.auto1.pantera.adapters.maven;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.maven.http.MavenProxySlice;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Maven proxy slice created from config.
 */
public final class MavenProxy implements Slice {

    private final Slice slice;

    /**
     * Construct a Maven proxy without metadata filtering.
     * Kept for backward compatibility; production wiring in
     * {@code RepositorySlices} uses the overload that takes
     * {@link CooldownMetadataService}.
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param queue Artifact events queue
     * @param cooldown Cooldown service
     */
    public MavenProxy(
        ClientSlices client, RepoConfig cfg, Optional<Queue<ProxyArtifactEvent>> queue,
        CooldownService cooldown
    ) {
        this(client, cfg, queue, cooldown, null);
    }

    /**
     * Construct a Maven proxy that filters upstream
     * {@code maven-metadata.xml} responses through the cooldown metadata
     * service before returning them to the client — fresh versions inside
     * the cooldown window are stripped from the {@code <versions>} list
     * and {@code <latest>} / {@code <release>} are rewritten downward.
     *
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param queue Artifact events queue.
     * @param cooldown Cooldown service (freshness enforcement on artifact
     *                 fetches).
     * @param cooldownMetadata Metadata filter service, or null to skip
     *                         filtering (tests, legacy deployments).
     */
    public MavenProxy(
        ClientSlices client, RepoConfig cfg, Optional<Queue<ProxyArtifactEvent>> queue,
        CooldownService cooldown, CooldownMetadataService cooldownMetadata
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        slice = new RaceSlice(
            cfg.remotes().stream().map(
                remote -> new MavenProxySlice(
                    client, remote.uri(),
                    GenericAuthenticator.create(client, remote.username(), remote.pwd()),
                    asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
                    asto.flatMap(ignored -> queue),
                    cfg.name(),
                    cfg.type(),
                    cooldown,
                    asto,  // Pass storage for checksum persistence
                    cooldownMetadata
                )
            ).collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    ) {
        return slice.response(line, headers, body);
    }
}
