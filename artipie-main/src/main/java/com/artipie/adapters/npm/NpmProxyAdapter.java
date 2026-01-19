/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.npm;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.metadata.CooldownMetadataService;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.GenericAuthenticator;
import com.artipie.http.group.GroupSlice;
import com.artipie.http.rq.RequestLine;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.npm.proxy.http.CachedNpmProxySlice;
import com.artipie.npm.proxy.http.NpmProxySlice;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.net.URL;
import java.time.Duration;
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
        this.slice = new GroupSlice(
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
                        Duration.ofHours(24),   // 404 cache TTL
                        true,                   // negative caching enabled
                        cfg.name(),             // repo name for cache isolation
                        remote.uri().toString(),// upstream URL for metrics
                        cfg.type()              // repository type
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
