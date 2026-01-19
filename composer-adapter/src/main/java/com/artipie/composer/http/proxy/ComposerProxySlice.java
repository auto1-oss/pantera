/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.cache.Cache;
import com.artipie.composer.Repository;
import com.artipie.composer.http.PackageMetadataSlice;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceSimple;
import com.artipie.scheduling.ProxyArtifactEvent;

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
            com.artipie.cooldown.NoopCooldownService.INSTANCE,
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
            com.artipie.cooldown.NoopCooldownService.INSTANCE,
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
     * @param baseUrl Base URL for this Artipie instance (for metadata URL rewriting)
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
     * @param baseUrl Base URL for this Artipie instance (for metadata URL rewriting)
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
