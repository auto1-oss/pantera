/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Queue;

/**
 * Maven proxy repository slice.
 * @since 0.5
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
public final class MavenProxySlice extends Slice.Wrap {

    /**
     * New maven proxy without cache.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Cache implementation
     */
    public MavenProxySlice(final ClientSlices clients, final URI remote,
        final Authenticator auth, final Cache cache) {
        this(clients, remote, auth, cache, Optional.empty(), "*",
            "maven-proxy", com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE, Optional.empty());
    }

    /**
     * Ctor for tests.
     * @param client Http client
     * @param uri Origin URI
     * @param authenticator Auth
     */
    MavenProxySlice(
        final JettyClientSlices client, final URI uri,
        final Authenticator authenticator
    ) {
        this(client, uri, authenticator, Cache.NOP, Optional.empty(), "*",
            "maven-proxy", com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE, Optional.empty(),
            Duration.ofHours(24), Duration.ofHours(24), true);
    }

    /**
     * New Maven proxy slice with cache.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param storage Storage for persisting checksums
     */
    public MavenProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown,
        final Optional<Storage> storage
    ) {
        this(clients, remote, auth, cache, events, rname, rtype, cooldown, storage,
            Duration.ofHours(24), Duration.ofHours(24), true);
    }

    /**
     * New Maven proxy slice with cache and configurable cache settings.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param storage Storage for persisting checksums
     * @param metadataTtl TTL for metadata cache
     * @param negativeCacheTtl TTL for negative cache (404s)
     * @param negativeCacheEnabled Whether negative caching is enabled
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public MavenProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown,
        final Optional<Storage> storage,
        final Duration metadataTtl,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(remote(clients, remote, auth), cache, events, rname, remote.toString(), rtype,
            cooldown, storage, metadataTtl);
    }

    /**
     * Internal constructor with resolved remote slice.
     * @param remote Resolved remote slice
     * @param cache Repository cache
     * @param events Artifact events queue
     * @param rname Repository name
     * @param upstreamUrl Upstream URL string
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param storage Storage for persisting checksums
     * @param metadataTtl TTL for metadata cache
     */
    private MavenProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String upstreamUrl,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown,
        final Optional<Storage> storage,
        final Duration metadataTtl
    ) {
        super(
            buildRoute(remote, cache, events, rname, upstreamUrl, rtype,
                cooldown, new MavenCooldownInspector(remote), storage, metadataTtl)
        );
    }

    /**
     * Build the routing slice with ChecksumProxySlice wrapping CachedProxySlice.
     */
    @SuppressWarnings({"PMD.ExcessiveParameterList", "PMD.CloseResource"})
    private static Slice buildRoute(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String upstreamUrl,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown,
        final MavenCooldownInspector inspector,
        final Optional<Storage> storage,
        final Duration metadataTtl
    ) {
        // Build ProxyCacheConfig with cooldown enabled so BaseCachedProxySlice
        // delegates to the cooldown service for freshness enforcement.
        final ProxyCacheConfig config = ProxyCacheConfig.withCooldown();
        // Create MetadataCache with provided TTL
        final com.auto1.pantera.cache.ValkeyConnection valkeyConn =
            com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().orElse(null);
        final MetadataCache metadataCache = new MetadataCache(
            metadataTtl,
            new MavenCacheConfig().metadataMaxSize(),
            valkeyConn,
            rname
        );
        return new SliceRoute(
            new RtRulePath(
                MethodRule.HEAD,
                new HeadProxySlice(remote)
            ),
            new RtRulePath(
                MethodRule.GET,
                new ChecksumProxySlice(
                    new CachedProxySlice(
                        remote, cache, events, rname, upstreamUrl, rtype,
                        cooldown, inspector, storage, config, metadataCache
                    )
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                new SliceSimple(ResponseBuilder.methodNotAllowed().build())
            )
        );
    }

    /**
     * Build client slice for target URI.
     *
     * @param client Client slices.
     * @param remote Remote URI.
     * @param auth Authenticator.
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
