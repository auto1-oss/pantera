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
package com.auto1.pantera.adapters.file;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.files.FileProxySlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * File proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 */
public final class FileProxy implements Slice {

    private final Slice slice;

    /**
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param events Artifact events queue
     * @param cooldown Cooldown service
     */
    public FileProxy(
        ClientSlices client, RepoConfig cfg, Optional<Queue<ArtifactEvent>> events,
        CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        
        // Support multiple remotes with GroupSlice (like maven-proxy)
        // Each remote gets its own FileProxySlice, evaluated in priority order
        this.slice = new RaceSlice(
            cfg.remotes().stream().map(
                remote -> new FileProxySlice(
                    new AuthClientSlice(
                        new UriClientSlice(client, remote.uri()),
                        GenericAuthenticator.create(client, remote.username(), remote.pwd())
                    ),
                    asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
                    asto.flatMap(ignored -> events),
                    cfg.name(),
                    cooldown,
                    remote.uri().toString()
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
