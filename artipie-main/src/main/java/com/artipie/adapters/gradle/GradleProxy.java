/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.gradle;

import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.FromStorageCache;
import com.artipie.asto.Content;
import com.artipie.cooldown.CooldownService;
import com.artipie.gradle.http.GradleProxySlice;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.client.auth.GenericAuthenticator;
import com.artipie.http.group.GroupSlice;
import com.artipie.http.rq.RequestLine;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Gradle proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 *
 * @since 1.0
 */
public final class GradleProxy implements Slice {

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
     */
    public GradleProxy(
        final JettyClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ProxyArtifactEvent>> queue,
        final CooldownService cooldown
    ) {
        final Optional<Storage> asto = cfg.storageOpt();
        
        // Support multiple remotes with GroupSlice (like maven-proxy)
        // Each remote gets its own GradleProxySlice, evaluated in priority order
        this.slice = new GroupSlice(
            cfg.remotes().stream().map(
                remote -> new GradleProxySlice(
                    client,
                    remote.uri(),
                    // Support per-remote authentication (like maven-proxy)
                    GenericAuthenticator.create(client, remote.username(), remote.pwd()),
                    asto.<Cache>map(FromStorageCache::new).orElse(Cache.NOP),
                    asto.flatMap(ignored -> queue),
                    cfg.name(),
                    cfg.type(),
                    cooldown,
                    asto  // Pass storage for checksum persistence
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
