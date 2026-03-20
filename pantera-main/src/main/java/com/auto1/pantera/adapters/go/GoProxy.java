/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.go;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.http.GoProxySlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.client.auth.GenericAuthenticator;
import com.auto1.pantera.http.group.GroupSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Go proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 *
 * @since 1.0
 */
public final class GoProxy implements Slice {

    /**
     * Underlying slice implementation.
     */
    private final Slice slice;

    /**
     * Ctor.
     *
     * @param client HTTP client
     * @param cfg Repository configuration
     * @param events Proxy artifact events
     * @param cooldown Cooldown service
     */
    public GoProxy(
        final JettyClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();

        // Support multiple remotes with GroupSlice (like maven-proxy)
        // Each remote gets its own GoProxySlice, evaluated in priority order
        this.slice = new GroupSlice(
            cfg.remotes().stream().map(
                remote -> new GoProxySlice(
                    client,
                    remote.uri(),
                    // Support per-remote authentication (like maven-proxy)
                    GenericAuthenticator.create(client, remote.username(), remote.pwd()),
                    asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
                    events,
                    asto,  // Pass storage for TTL-based metadata caching
                    cfg.name(),
                    cfg.type(),
                    cooldown
                )
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
