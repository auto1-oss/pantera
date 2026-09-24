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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
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
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;

/**
 * Python proxy slice.
 * @since 0.7
 */
public final class PyProxySlice extends Slice.Wrap {

    /**
     * Path of a distribution file.
     */
    private static final String FILE = ".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)";

    /**
     * Any path that is not a distribution file.
     */
    private static final String NOT_A_FILE = "(?!" + PyProxySlice.FILE + "$).*";

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
            new RegistryBackedInspector(rtype, PublishDateRegistries.instance())
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
        final CooldownInspector inspector
    ) {
        this(
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
                inspector,
                // PyPI JSON API upstream — always pypi.org, regardless of the
                // Simple-API mirror configured. Used by PypiJsonHandler to
                // serve cooldown-filtered /pypi/{pkg}/{ver}/json responses.
                new UriClientSlice(clients, jsonApiUri(remote)),
                // Mirror credential binding (2.2.9): index links may name
                // any host; the upstream credentials go only to hosts
                // trusted for THIS remote.
                CacheTimeControl.DEFAULT_TTL,
                remote
            )
        );
    }

    /**
     * Routes over the proxy. A proxy is read-only: GET is proxied; HEAD of an
     * index page is answered like GET without the body (a group relays
     * client HEAD probes to its members); HEAD of a distribution file is
     * refused rather than turned into a full upstream download. Refusals
     * carry the {@code Allow} header.
     *
     * @param proxy Proxy slice answering GET
     */
    private PyProxySlice(final Slice proxy) {
        super(
            new SliceRoute(
                new RtRulePath(MethodRule.GET, proxy),
                new RtRulePath(
                    new RtRule.All(MethodRule.HEAD, new RtRule.ByPath(PyProxySlice.NOT_A_FILE)),
                    new HeadAsGetSlice(proxy)
                ),
                new RtRulePath(
                    new RtRule.ByPath(PyProxySlice.FILE), PyProxySlice.notAllowed("GET")
                ),
                new RtRulePath(RtRule.FALLBACK, PyProxySlice.notAllowed("GET, HEAD"))
            )
        );
    }

    /**
     * 405 refusal naming the allowed methods; the request body is drained.
     *
     * @param allow Allowed methods
     * @return Slice
     */
    private static Slice notAllowed(final String allow) {
        return (line, headers, body) -> body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.methodNotAllowed().header("Allow", allow).build()
        );
    }

    private static URI jsonApiUri(final URI remote) {
        final String scheme = remote.getScheme();
        final String authority = remote.getRawAuthority();
        if (scheme == null || authority == null) {
            return remote;
        }
        return URI.create(String.format("%s://%s", scheme, authority));
    }

}
