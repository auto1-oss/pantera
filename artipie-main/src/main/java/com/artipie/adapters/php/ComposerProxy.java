/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.php;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.composer.AstoRepository;
import com.artipie.composer.http.proxy.ComposerProxySlice;
import com.artipie.composer.http.proxy.ComposerStorageCache;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.auth.GenericAuthenticator;
import com.artipie.http.group.GroupSlice;
import com.artipie.http.rq.RequestLine;
import com.artipie.settings.repo.RepoConfig;

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
        this(client, cfg, Optional.empty(), com.artipie.cooldown.NoopCooldownService.INSTANCE);
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
        Optional<Queue<com.artipie.scheduling.ProxyArtifactEvent>> events,
        com.artipie.cooldown.CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        final String baseUrl = cfg.url().toString();
        
        // Support multiple remotes with GroupSlice (like maven-proxy)
        // Each remote gets its own ComposerProxySlice, evaluated in priority order
        this.slice = new GroupSlice(
            cfg.remotes().stream().map(
                remote -> {
                    final com.artipie.http.client.auth.Authenticator auth = 
                        GenericAuthenticator.create(client, remote.username(), remote.pwd());
                    final Slice remoteSlice = new com.artipie.http.client.auth.AuthClientSlice(
                        new com.artipie.http.client.UriClientSlice(client, remote.uri()),
                        auth
                    );
                    
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
                            new com.artipie.composer.http.proxy.ComposerCooldownInspector(remoteSlice),
                            baseUrl
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
                            new com.artipie.composer.http.proxy.ComposerCooldownInspector(remoteSlice),
                            baseUrl
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
