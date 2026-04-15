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
package com.auto1.pantera.adapters.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.group.RaceSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.proxy.http.CachedNpmProxySlice;
import com.auto1.pantera.npm.proxy.http.NpmProxySlice;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.net.URL;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * NPM proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 *
 * @since 1.0
 */
public final class NpmProxyAdapter implements Slice {

    /**
     * Underlying slice implementation.
     */
    private final Slice slice;

    /**
     * Ctor.
     *
     * @param client HTTP client
     * @param cfg Repository configuration
     * @param queue Proxy artifact events queue
     * @param cooldown Cooldown service
     * @param cooldownMetadata Cooldown metadata filtering service
     */
    public NpmProxyAdapter(
        final ClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ProxyArtifactEvent>> queue,
        final CooldownService cooldown,
        final CooldownMetadataService cooldownMetadata
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        final Optional<URL> baseUrl = Optional.of(cfg.url());
        
        // Support multiple remotes with GroupSlice (similar to maven-proxy).
        // Each remote gets its own NpmProxy + NpmProxySlice, evaluated in
        // priority order.
        this.slice = new RaceSlice(
            cfg.remotes().stream().map(
                remote -> {
                    // Create authenticated client slice for this remote
                    final Slice remoteSlice = new AuthClientSlice(
                        new UriClientSlice(client, remote.uri()),
                        GenericAuthenticator.create(client, remote.username(), remote.pwd())
                    );
                    
                    // Create NpmProxy for this remote with 12h metadata TTL
                    final NpmProxy npmProxy = new NpmProxy(
                        asto.orElseThrow(() -> new IllegalStateException(
                            "npm-proxy requires storage to be set"
                        )),
                        remoteSlice,
                        NpmProxy.DEFAULT_METADATA_TTL
                    );
                    
                    // Wrap with NpmProxySlice
                    final Slice npmProxySlice = new NpmProxySlice(
                        "",  // Proxy repos don't need path prefix - routing handled by repo name
                        npmProxy,
                        queue,
                        cfg.name(),
                        cfg.type(),
                        cooldown,
                        cooldownMetadata,
                        remoteSlice,  // For security audit pass-through
                        baseUrl
                    );
                    
                    // Wrap with caching layer to prevent repeated 404 requests
                    return new CachedNpmProxySlice(
                        npmProxySlice,
                        asto,
                        cfg.name(),
                        remote.uri().toString(),
                        cfg.type()
                    );
                }
            ).collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.slice.response(line, headers, body);
    }
}
