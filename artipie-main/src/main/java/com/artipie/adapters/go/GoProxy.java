/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.go;

import com.artipie.asto.cache.FromStorageCache;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.GoProxySlice;
import com.artipie.http.Slice;
import com.artipie.http.client.RemoteConfig;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.settings.repo.RepoConfig;

import java.util.Optional;
import java.util.Queue;

/**
 * Go proxy adapter.
 *
 * @since 1.0
 */
public final class GoProxy {

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
    public GoProxy(
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
     * @return Go proxy slice
     */
    public Slice slice() {
        final RemoteConfig remote = this.cfg.remoteConfig();
        
        return new GoProxySlice(
            this.client,
            remote.uri(),
            Authenticator.ANONYMOUS,
            new FromStorageCache(this.cfg.storage()),
            this.events,
            this.cfg.name(),
            this.cfg.type(),
            this.cooldown
        );
    }
}
