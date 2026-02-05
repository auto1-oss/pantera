/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.RedirectFollowingSlice;
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
 * Gradle proxy repository slice.
 *
 * @since 1.0
 */
public final class GradleProxySlice extends Slice.Wrap {

    /**
     * New gradle proxy without cache.
     *
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Cache implementation
     */
    public GradleProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Cache cache
    ) {
        this(
            clients, remote, auth, cache, Optional.empty(), "*",
            "gradle-proxy", com.artipie.cooldown.NoopCooldownService.INSTANCE, Optional.empty()
        );
    }

    /**
     * Ctor for tests.
     *
     * @param client Http client
     * @param uri Origin URI
     * @param authenticator Auth
     */
    GradleProxySlice(
        final ClientSlices client,
        final URI uri,
        final Authenticator authenticator
    ) {
        this(
            client, uri, authenticator, Cache.NOP, Optional.empty(), "*",
            "gradle-proxy", com.artipie.cooldown.NoopCooldownService.INSTANCE, Optional.empty()
        );
    }

    /**
     * New Gradle proxy slice with cache.
     *
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
    public GradleProxySlice(
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
        this(remote(clients, remote, auth), cache, events, rname, rtype, cooldown, storage);
    }

    private GradleProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final Optional<Storage> storage
    ) {
        this(remote, cache, events, rname, rtype, cooldown, new GradleCooldownInspector(remote), storage);
    }

    private GradleProxySlice(
        final Slice remote,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final com.artipie.cooldown.CooldownService cooldown,
        final GradleCooldownInspector inspector,
        final Optional<Storage> storage
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.HEAD,
                    new HeadProxySlice(remote)
                ),
                new RtRulePath(
                    MethodRule.GET,
                    new CachedProxySlice(remote, cache, events, rname, rtype, cooldown, inspector, storage)
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.methodNotAllowed().build())
                )
            )
        );
    }

    /**
     * Build client slice for target URI with redirect support.
     * <p>
     * Wraps the remote slice with RedirectFollowingSlice to handle
     * cross-domain CDN redirects that some registries use.
     *
     * @param client Client slices.
     * @param remote Remote URI.
     * @param auth Authenticator.
     * @return Client slice for target URI with redirect support.
     */
    private static Slice remote(
        final ClientSlices client,
        final URI remote,
        final Authenticator auth
    ) {
        return new RedirectFollowingSlice(
            new AuthClientSlice(new UriClientSlice(client, remote), auth),
            client
        );
    }
}
