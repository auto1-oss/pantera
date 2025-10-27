/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.gradle;

import com.artipie.asto.cache.FromStorageCache;
import com.artipie.cooldown.CooldownService;
import com.artipie.gradle.http.GradleProxySlice;
import com.artipie.http.Slice;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;

/**
 * Gradle proxy adapter.
 *
 * @since 1.0
 */
public final class GradleProxy {

    /**
     * HTTP client.
     */
    private final JettyClientSlices client;

    /**
     * Repository configuration.
     */
    private final RepoConfig cfg;

    /**
     * Proxy artifact events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Ctor.
     *
     * @param client HTTP client
     * @param cfg Repository configuration
     * @param events Proxy artifact events
     * @param cooldown Cooldown service
     */
    public GradleProxy(
        final JettyClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final CooldownService cooldown
    ) {
        this.client = client;
        this.cfg = cfg;
        this.events = events;
        this.cooldown = cooldown;
    }

    /**
     * Create slice.
     *
     * @return Gradle proxy slice
     */
    public Slice slice() {
        // Support standard 'remotes' format for consistency with other proxy adapters
        final URI remote = this.cfg.remotes().stream()
            .findFirst()
            .map(remoteConfig -> remoteConfig.uri())
            .orElseThrow(() -> new IllegalStateException("Remote URL is required for gradle-proxy"));
        
        return new GradleProxySlice(
            this.client,
            remote,
            Authenticator.ANONYMOUS,
            new FromStorageCache(this.cfg.storage()),
            this.events,
            this.cfg.name(),
            this.cfg.type(),
            this.cooldown,
            this.cfg.storageOpt()  // Pass storage for checksum persistence
        );
    }
}
