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
package com.auto1.pantera.adapters.php;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.http.proxy.ComposerProxySlice;
import com.auto1.pantera.composer.http.proxy.ComposerStorageCache;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Composer/PHP proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 */
public final class ComposerProxy implements Slice {

    private final Slice slice;

    /**
     * @param client HTTP client
     * @param cfg Repository configuration
     */
    public ComposerProxy(ClientSlices client, RepoConfig cfg) {
        this(client, cfg, Optional.empty(), com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE);
    }

    /**
     * Full constructor with event queue and cooldown support.
     * @param client HTTP client
     * @param cfg Repository configuration
     * @param events Proxy artifact events queue
     * @param cooldown Cooldown service
     */
    public ComposerProxy(
        ClientSlices client,
        RepoConfig cfg,
        Optional<Queue<com.auto1.pantera.scheduling.ProxyArtifactEvent>> events,
        com.auto1.pantera.cooldown.api.CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        final String baseUrl = cfg.url().toString();
        
        // Support multiple remotes with GroupResolver (like maven-proxy)
        // Each remote gets its own ComposerProxySlice, evaluated in priority order
        this.slice = new RaceSlice(
            cfg.remotes().stream().map(
                remote -> {
                    final com.auto1.pantera.http.client.auth.Authenticator auth =
                        GenericAuthenticator.create(client, remote.username(), remote.pwd());

                    return asto.map(
                        cache -> new ComposerProxySlice(
                            client,
                            remote.uri(),
                            new AstoRepository(cfg.storage()),
                            auth,
                            new ComposerStorageCache(new AstoRepository(cache)),
                            events,
                            cfg.name(),
                            cfg.type(),
                            cooldown,
                            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                                "composer",
                                com.auto1.pantera.publishdate.PublishDateRegistries.instance()
                            ),
                            baseUrl,
                            remote.uri().toString()
                        )
                    ).orElseGet(
                        () -> new ComposerProxySlice(
                            client,
                            remote.uri(),
                            new AstoRepository(cfg.storage()),
                            auth,
                            new ComposerStorageCache(new AstoRepository(asto.orElse(cfg.storage()))),
                            events,
                            cfg.name(),
                            cfg.type(),
                            cooldown,
                            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                                "composer",
                                com.auto1.pantera.publishdate.PublishDateRegistries.instance()
                            ),
                            baseUrl,
                            remote.uri().toString()
                        )
                    );
                }
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
