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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.composer.http.PackageMetadataSlice;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;

/**
 * Composer proxy repository slice.
 */
public class ComposerProxySlice extends Slice.Wrap {
    /**
     * New Composer proxy without cache.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param repo Repository
     * @param auth Authenticator
     */
    public ComposerProxySlice(
        final ClientSlices clients, final URI remote,
        final Repository repo, final Authenticator auth
    ) {
        this(clients, remote, repo, auth, Cache.NOP, Optional.empty(), "composer", "php",
            com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE,
            new NoopComposerCooldownInspector(),
            "http://localhost:8080");
    }

    /**
     * New Composer proxy slice with cache.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param repository Repository
     * @param auth Authenticator
     * @param cache Repository cache
     */
    public ComposerProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Repository repository,
        final Authenticator auth,
        final Cache cache
    ) {
        this(clients, remote, repository, auth, cache, Optional.empty(), "composer", "php",
            com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE,
            new NoopComposerCooldownInspector(),
            "http://localhost:8080");
    }
    
    /**
     * Full constructor with cooldown support.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param repository Repository
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param baseUrl Base URL for this Pantera instance (for metadata URL rewriting)
     */
    public ComposerProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Repository repository,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String baseUrl
    ) {
        this(clients, remote, repository, auth, cache, events, rname, rtype, cooldown, inspector, baseUrl, remote.toString());
    }

    /**
     * Full constructor with upstream URL for metrics.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param repository Repository
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param baseUrl Base URL for this Pantera instance (for metadata URL rewriting)
     * @param upstreamUrl Upstream URL for metrics
     */
    public ComposerProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Repository repository,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String baseUrl,
        final String upstreamUrl
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(PackageMetadataSlice.ALL_PACKAGES),
                        MethodRule.GET
                    ),
                    new SliceSimple(
                        () -> ResponseBuilder.ok()
                            .jsonBody(
                                String.format(
                                    "{\"packages\":{}, \"metadata-url\":\"/%s/p2/%%package%%.json\"}",
                                    rname
                                )
                            )
                            .build()
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(PackageMetadataSlice.PACKAGE),
                        MethodRule.GET
                    ),
                    new CachedProxySlice(
                        remote(clients, remote, auth),
                        repository,
                        cache,
                        events,
                        rname,
                        rtype,
                        cooldown,
                        inspector,
                        baseUrl,
                        upstreamUrl
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    // Proxy all other requests (zip files, etc.) through to remote
                    new ProxyDownloadSlice(
                        remote(clients, remote, auth),
                        clients,
                        remote,
                        events,
                        rname,
                        rtype,
                        repository.storage(),
                        cooldown,
                        inspector
                    )
                )
            )
        );
    }

    /**
     * Build client slice for target URI.
     * @param client Client slices
     * @param remote Remote URI
     * @param auth Authenticator
     * @return Client slice for target URI.
     */
    private static Slice remote(
        final ClientSlices client,
        final URI remote,
        final Authenticator auth
    ) {
        return new AuthClientSlice(new UriClientSlice(client, remote), auth);
    }
}
