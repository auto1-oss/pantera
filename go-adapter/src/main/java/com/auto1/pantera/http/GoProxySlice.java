/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
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
import java.util.Optional;
import java.util.Queue;

/**
 * Go proxy repository slice.
 *
 * @since 1.0
 */
public final class GoProxySlice extends Slice.Wrap {

    /**
     * New Go proxy without cache.
     *
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Cache implementation
     */
    public GoProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache
    ) {
        this(
            clients, remote, auth, cache, Optional.empty(), Optional.empty(), "*",
            "go-proxy", com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE
        );
    }

    /**
     * Ctor for tests.
     *
     * @param client Http client
     * @param uri Origin URI
     * @param authenticator Auth
     */
    GoProxySlice(
        final JettyClientSlices client,
        final URI uri,
        final Authenticator authenticator
    ) {
        this(
            client, uri, authenticator, Cache.NOP, Optional.empty(), Optional.empty(), "*",
            "go-proxy", com.auto1.pantera.cooldown.NoopCooldownService.INSTANCE
        );
    }

    /**
     * New Go proxy slice with cache.
     *
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     */
    public GoProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown
    ) {
        this(clients, remote, auth, cache, events, Optional.empty(), rname, rtype, cooldown);
    }

    /**
     * New Go proxy slice with cache and storage for TTL-based metadata caching.
     *
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Repository cache
     * @param events Artifact events queue
     * @param storage Optional storage for TTL-based metadata cache
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     */
    public GoProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final Optional<Storage> storage,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown
    ) {
        this(remote(clients, remote, auth), cache, events, storage, rname, rtype, cooldown);
    }

    GoProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final Optional<Storage> storage,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown
    ) {
        this(remote, cache, events, storage, rname, rtype, cooldown, new GoCooldownInspector(remote));
    }

    GoProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final Optional<Storage> storage,
        final String rname,
        final String rtype,
        final com.auto1.pantera.cooldown.CooldownService cooldown,
        final GoCooldownInspector inspector
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.HEAD,
                    new HeadProxySlice(remote)
                ),
                new RtRulePath(
                    MethodRule.GET,
                    new CachedProxySlice(remote, cache, events, storage, rname, rtype, cooldown, inspector)
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
