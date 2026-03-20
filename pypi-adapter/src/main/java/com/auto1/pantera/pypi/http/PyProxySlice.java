/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.asto.Storage;
import com.artipie.asto.cache.FromStorageCache;
import com.artipie.asto.cache.StreamThroughCache;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.NoopCooldownService;
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
 * Python proxy slice.
 * @since 0.7
 */
public final class PyProxySlice extends Slice.Wrap {

    /**
     * New maven proxy without cache.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param storage Cache storage
     */
    public PyProxySlice(final ClientSlices clients, final URI remote, final Storage storage) {
        this(
            clients,
            remote,
            Authenticator.ANONYMOUS,
            storage,
            Optional.empty(),
            "*",
            "pypi-proxy",
            NoopCooldownService.INSTANCE
        );
    }

    /**
     * Ctor.
     * @param clients Http clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Repository cache storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public PyProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Storage cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname
    ) {
        this(clients, remote, auth, cache, events, rname, "pypi-proxy", NoopCooldownService.INSTANCE);
    }

    public PyProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Storage cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown
    ) {
        this(
            clients,
            remote,
            auth,
            cache,
            events,
            rname,
            rtype,
            cooldown,
            new PyProxyCooldownInspector(
                // Always use pypi.org for JSON API, regardless of Simple API upstream
                new UriClientSlice(
                    clients,
                    jsonApiUri(remote)
                )
            )
        );
    }

    private PyProxySlice(
        final ClientSlices clients,
        final URI remote,
        final Authenticator auth,
        final Storage cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final PyProxyCooldownInspector inspector
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.GET,
                    new ProxySlice(
                        clients,
                        auth,
                        new AuthClientSlice(new UriClientSlice(clients, remote), auth),
                        cache,
                        new StreamThroughCache(cache),
                        events,
                        rname,
                        rtype,
                        cooldown,
                        registerInspector(rtype, rname, inspector)
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.methodNotAllowed().build())
                )
            )
        );
    }

    private static URI baseUri(final URI remote) {
        final String scheme = remote.getScheme();
        final String authority = remote.getRawAuthority();
        if (scheme == null || authority == null) {
            return remote;
        }
        return URI.create(String.format("%s://%s", scheme, authority));
    }

    /**
     * Extract JSON API base URI from remote URI.
     * For pypi.org/simple → pypi.org
     * For custom-pypi.com/simple → custom-pypi.com
     * For pypi.org → pypi.org (unchanged)
     *
     * @param remote Remote URI
     * @return Base URI for JSON API calls
     */
    private static URI jsonApiUri(final URI remote) {
        return baseUri(remote);
    }

    /**
     * Register inspector and return it (helper for constructor).
     */
    private static PyProxyCooldownInspector registerInspector(
        final String rtype,
        final String rname,
        final PyProxyCooldownInspector inspector
    ) {
        com.artipie.cooldown.InspectorRegistry.instance()
            .register(rtype, rname, inspector);
        return inspector;
    }

}
