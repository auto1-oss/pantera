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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.composer.cooldown.ComposerPackageMetadataHandler;
import com.auto1.pantera.composer.cooldown.ComposerRootPackagesHandler;
import com.auto1.pantera.composer.http.PackageMetadataSlice;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Composer proxy repository slice.
 *
 * <p>Dispatch order (cooldown-aware):</p>
 * <ol>
 *   <li>{@link ComposerRootPackagesHandler} for {@code /packages.json}
 *       and {@code /repo.json} — filters blocked versions out of
 *       inline root aggregation shapes before the response leaves
 *       the proxy.</li>
 *   <li>{@link ComposerPackageMetadataHandler} for
 *       {@code /p2/<vendor>/<pkg>.json} and
 *       {@code /packages/<vendor>/<pkg>.json} — filters blocked
 *       versions out of per-package metadata. This is where the
 *       {@code composerBundle} registered in {@code CooldownWiring}
 *       is actually consumed on the serve path.</li>
 *   <li>Fallback to the legacy {@link SliceRoute} that services
 *       archive downloads and non-metadata requests via
 *       {@link CachedProxySlice} / {@link ProxyDownloadSlice}.</li>
 * </ol>
 *
 * <p>Mirrors the handler-dispatch pattern established by
 * {@code GoListHandler} ({@code 1eb53ceb}), {@code PypiSimpleHandler}
 * ({@code 19bc60cb}) and {@code DockerTagsListHandler}
 * ({@code 6c5a30ef}).</p>
 */
@SuppressWarnings("PMD.TooManyMethods")
public class ComposerProxySlice implements Slice {

    /**
     * Fallback slice-route for archive downloads and non-cooldown
     * metadata endpoints. Built once per instance so the per-request
     * dispatch path stays O(1).
     */
    private final Slice fallback;

    /**
     * Cooldown handler for {@code /packages.json} / {@code /repo.json}
     * root aggregation filtering. {@code null} when cooldown is
     * disabled (no-op service) — the dispatch check short-circuits.
     */
    private final ComposerRootPackagesHandler rootHandler;

    /**
     * Cooldown handler for per-package metadata filtering.
     * {@code null} when cooldown is disabled.
     */
    private final ComposerPackageMetadataHandler packageHandler;

    /**
     * Whether cooldown handlers are live. When false, the instance
     * behaves identically to the pre-cooldown version and all
     * requests route through {@link #fallback}.
     */
    private final boolean cooldownEnabled;

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
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE,
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
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE,
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
        this.fallback = new SliceRoute(
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
        );
        // Build the cooldown upstream slice lazily — the handler fetch
        // goes directly to the remote (with auth) rather than through
        // the fallback SliceRoute, so we avoid re-entering our own
        // dispatcher. This mirrors PypiSimpleHandler's
        // `simpleUpstream = serveNonArtifact(...)` pattern and
        // DockerTagsListHandler's direct upstream slice.
        final Slice cooldownUpstream = remote(clients, remote, auth);
        this.cooldownEnabled = !(cooldown
            instanceof com.auto1.pantera.cooldown.impl.NoopCooldownService);
        if (this.cooldownEnabled) {
            this.rootHandler = new ComposerRootPackagesHandler(
                cooldownUpstream, cooldown, inspector, rtype, rname
            );
            this.packageHandler = new ComposerPackageMetadataHandler(
                cooldownUpstream, cooldown, inspector, rtype, rname
            );
        } else {
            this.rootHandler = null;
            this.packageHandler = null;
        }
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (!this.cooldownEnabled) {
            return this.fallback.response(line, headers, body);
        }
        final String path = line.uri().getPath();
        final String user = new Login(headers).getValue();
        // Cooldown handlers run ahead of the legacy route so blocked
        // versions cannot leak through the root / per-package
        // metadata surfaces. Mirrors the Go / PyPI / Docker
        // dispatch pattern (1eb53ceb, 19bc60cb, 6c5a30ef).
        if (this.rootHandler != null && this.rootHandler.matches(path)) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Dispatching root packages request to cooldown root handler")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();
            return body.asBytesFuture()
                .thenCompose(ignored -> this.rootHandler.handle(line, user));
        }
        if (this.packageHandler != null && this.packageHandler.matches(path)) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Dispatching per-package metadata to cooldown handler")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();
            return body.asBytesFuture()
                .thenCompose(ignored -> this.packageHandler.handle(line, user));
        }
        return this.fallback.response(line, headers, body);
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
