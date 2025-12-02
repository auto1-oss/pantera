/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.pypi;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.auth.GenericAuthenticator;
import com.artipie.http.group.GroupSlice;
import com.artipie.http.rq.RequestLine;
import com.artipie.pypi.http.CachedPyProxySlice;
import com.artipie.pypi.http.PyProxySlice;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * PyPI proxy adapter with maven-proxy feature parity.
 * Supports multiple remotes, authentication, priority ordering, and failover.
 */
public final class PypiProxy implements Slice {

    private final Slice slice;

    /**
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param queue Artifact events queue
     * @param cooldown Cooldown service
     */
    public PypiProxy(
        ClientSlices client,
        RepoConfig cfg,
        Optional<Queue<ProxyArtifactEvent>> queue,
        CooldownService cooldown
    ) {
        final Storage storage = cfg.storageOpt().orElseThrow(
            () -> new IllegalStateException("PyPI proxy requires storage to be set")
        );
        
        // Support multiple remotes with GroupSlice (like maven-proxy)
        // Each remote gets its own PyProxySlice, evaluated in priority order
        this.slice = new GroupSlice(
            cfg.remotes().stream().map(
                remote -> {
                    // Create PyProxySlice for this remote
                    final Slice pyProxySlice = new PyProxySlice(
                        client,
                        remote.uri(),
                        GenericAuthenticator.create(client, remote.username(), remote.pwd()),
                        storage,
                        queue,
                        cfg.name(),
                        cfg.type(),
                        cooldown
                    );
                    
                    // Wrap with caching layer to prevent repeated 404 requests
                    return new CachedPyProxySlice(
                        pyProxySlice,
                        Optional.of(storage),
                        Duration.ofHours(24),  // 404 cache TTL
                        true,                   // negative caching enabled
                        cfg.name(),             // CRITICAL: Pass repo name for cache isolation
                        remote.uri().toString(), // Upstream URL for metrics
                        cfg.type()              // Repository type
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
        return slice.response(line, headers, body);
    }
}
