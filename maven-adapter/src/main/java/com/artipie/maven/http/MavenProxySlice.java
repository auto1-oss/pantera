/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceSimple;
import com.artipie.scheduling.ProxyArtifactEvent;

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
            "maven-proxy", com.artipie.cooldown.NoopCooldownService.INSTANCE, Optional.empty());
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
            "maven-proxy", com.artipie.cooldown.NoopCooldownService.INSTANCE, Optional.empty(),
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
        final com.artipie.cooldown.CooldownService cooldown,
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
    public MavenProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final Optional<Storage> storage,
        final Duration metadataTtl,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(remote(clients, remote, auth), cache, events, rname, remote.toString(), rtype, cooldown, storage,
            metadataTtl, negativeCacheTtl, negativeCacheEnabled);
    }

    private MavenProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String upstreamUrl,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final Optional<Storage> storage
    ) {
        this(remote, cache, events, rname, upstreamUrl, rtype, cooldown, storage,
            Duration.ofHours(24), Duration.ofHours(24), true);
    }

    private MavenProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String upstreamUrl,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final Optional<Storage> storage,
        final Duration metadataTtl,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(remote, cache, events, rname, upstreamUrl, rtype, cooldown, new MavenCooldownInspector(remote),
            storage, metadataTtl, negativeCacheTtl, negativeCacheEnabled);
    }

    private MavenProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String upstreamUrl,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final MavenCooldownInspector inspector,
        final Optional<Storage> storage,
        final Duration metadataTtl,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.HEAD,
                    new HeadProxySlice(remote)
                ),
                new RtRulePath(
                    MethodRule.GET,
                    // Wrap with ChecksumProxySlice to auto-generate .sha1/.md5 files
                    // This dramatically improves Maven client performance by eliminating
                    // "Checksum validation failed, no checksums available" errors
                    new ChecksumProxySlice(
                        new CachedProxySlice(remote, cache, events, rname, upstreamUrl, rtype, cooldown, inspector,
                            storage, metadataTtl, negativeCacheTtl, negativeCacheEnabled)
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.methodNotAllowed().build())
                )
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
