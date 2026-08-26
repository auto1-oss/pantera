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
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.composer.cooldown.ComposerPackageMetadataHandler;
import com.auto1.pantera.composer.cooldown.ComposerRootPackagesHandler;
import com.auto1.pantera.composer.http.PackageMetadataSlice;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Composer proxy repository slice.
 *
 * <p>Dispatch order (cooldown-aware):</p>
 * <ol>
 *   <li>{@link ComposerRootPackagesHandler} for {@code /packages.json}
 *       and {@code /repo.json} — fetches the raw upstream root
 *       (bootstraps a standalone proxy even with no local member),
 *       filters blocked versions out of inline root aggregation
 *       shapes, and rewrites every top-level URL field to a
 *       Pantera-local equivalent so a client cannot be steered
 *       straight to the upstream from the root.</li>
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
     */
    private final ComposerPackageMetadataHandler packageHandler;

    /**
     * Raw (unrewritten, uncached) upstream slice — shared with the root
     * handler. Also backs the WS4-composer.5/.6 catalog-surface routes
     * ({@code available-packages.json}, {@code packages/list.json}):
     * these are live-passthrough (not cached) because, unlike a single
     * package's metadata, the catalog surfaces enumerate the ENTIRE
     * upstream registry (hundreds of thousands of packages on Packagist)
     * — not meaningfully cacheable at per-repository scale, and rarely on
     * the hot path of a {@code composer install}.
     */
    private final Slice rawRemote;

    /**
     * Repository type, threaded to the catalog-surface passthrough routes
     * for audit records.
     */
    private final String rtype;

    /**
     * Repository name, threaded to the catalog-surface passthrough routes
     * for audit records.
     */
    private final String rname;

    /**
     * {@code /p2/available-packages.json} — advertised by
     * {@link com.auto1.pantera.composer.SatisLayout} and rewritten
     * Pantera-local by {@link MetadataUrlRewriter#rewriteRoot}; served
     * here as a live passthrough (WS4-composer.5) so the surface does not
     * 404 once advertised.
     */
    private static final Pattern AVAILABLE_PACKAGES = Pattern.compile(
        "^/p2/available-packages\\.json$"
    );

    /**
     * {@code /packages/list.json} (optionally {@code ?q=&type=}) —
     * rewritten Pantera-local by {@link MetadataUrlRewriter#rewriteRoot}
     * (both {@code list} and {@code search} point here); served here as a
     * live passthrough (WS4-composer.6).
     */
    private static final Pattern LIST_JSON = Pattern.compile(
        "^/packages/list\\.json$"
    );

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
            new RegistryBackedInspector("composer", PublishDateRegistries.instance()),
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
            new RegistryBackedInspector("composer", PublishDateRegistries.instance()),
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
        // Raw upstream slice — shared by the primary-artifact fetch inside
        // CachedProxySlice, ProxyDownloadSlice, and the root handler.
        final Slice rawRemote = remote(clients, remote, auth);
        // Build the cache+rewrite slice once and share it between the
        // fallback SliceRoute (cooldown-off path) and the per-package
        // cooldown handler (cooldown-on path). A raw-remote slice for the
        // per-package handler would bypass the metadata cache AND the
        // dist.url rewriter — so on the cooldown-enabled path,
        // /p2/<vendor>/<pkg>.json responses would still point Composer at
        // the upstream (api.github.com / packagist) for archive downloads,
        // breaking the proxy. Sharing the slice keeps cache + URL
        // rewriting + primary-artifact integrity intact on both paths,
        // with per-version cooldown filtering layered on top.
        final CachedProxySlice cachedProxy = new CachedProxySlice(
            rawRemote,
            repository,
            cache,
            events,
            rname,
            baseUrl,
            upstreamUrl
        );
        this.fallback = new SliceRoute(
            new RtRulePath(
                new RtRule.All(
                    new RtRule.ByPath(PackageMetadataSlice.PACKAGE),
                    MethodRule.GET
                ),
                cachedProxy
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                // Proxy all other requests (zip files, etc.) through to remote
                new ProxyDownloadSlice(
                    rawRemote,
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
        // Handlers are constructed UNCONDITIONALLY — including when the
        // cooldown service is the Noop instance. They are the only place
        // the per-request artifact_resolution audit record fires for
        // Composer metadata, and the taxonomy contract is that every
        // metadata listing view is audited whether filtering is configured
        // or not. With NoopCooldownService, evaluateWithKnownDate always
        // returns "allowed", so the handlers pass metadata through
        // unfiltered — behaviourally identical to the old
        // skip-handlers-when-noop gate, minus the audit blackout.
        //
        // The ROOT handler fetches the RAW remote — not cachedProxy.
        // cachedProxy's package-merge path keys its cache/merge lookup on
        // a single package name derived from the request path; fed
        // "/packages.json" it mangles the path into a bogus package name
        // ("/packages"), which can never merge successfully and always
        // 404s. There is no per-package name for a root aggregation
        // document, so the root is fetched directly and rewritten here
        // (top-level URLs to Pantera-local via MetadataUrlRewriter,
        // per-version cooldown filtering via ComposerRootPackagesFilter)
        // rather than routed through the merge cache.
        //
        // The PACKAGE handler fetches through the shared cache+rewrite
        // slice (rather than re-entering this dispatcher) — so metadata
        // is served from cache with dist.url already rewritten, and the
        // handler just layers per-version filtering on top. Per-version
        // release dates come from the packument's inline {@code time}
        // field (Composer always inlines them); the CooldownInspector is
        // therefore not threaded through the handler path — the evaluator
        // uses {@code evaluateWithKnownDate} which skips inspector lookup
        // entirely. Mirrors the npm/PyPI packument-inline pattern landed
        // in {@code dbdde1736}.
        // WS6.3: route the root aggregation surface through the same
        // cache/storage this repository already uses for per-package
        // metadata — TTL-cached, single-flighted, serve-stale-on-outage
        // (ComposerRootBaseLoader), instead of hitting the upstream
        // unconditionally on every /packages.json or /repo.json request.
        // ComposerRootBaseLoader namespaces its keys so they never collide
        // with the per-package cache entries.
        this.rootHandler = new ComposerRootPackagesHandler(
            rawRemote, cache, repository.storage(), cooldown, rtype, rname, baseUrl
        );
        this.packageHandler = new ComposerPackageMetadataHandler(
            cachedProxy, cooldown, rtype, rname
        );
        this.rawRemote = rawRemote;
        this.rtype = rtype;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (line.method() == RqMethod.HEAD) {
            return this.headAsGet(line, headers, body);
        }
        final String path = line.uri().getPath();
        final String user = new Login(headers).getValue();
        // Bound as early as possible — before any async hop — so the
        // AuditLogger.resolution() call downstream in the cooldown
        // handlers gets real trace.id / client.ip instead of nulls from
        // a worker thread that never had EcsLoggingSlice's MDC bound.
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext auditCtx = new AuditContext(
            org.slf4j.MDC.get(EcsMdc.TRACE_ID),
            org.slf4j.MDC.get(EcsMdc.CLIENT_IP)
        );
        // WS4-composer.5/.6: available-packages / search-list catalog
        // surfaces. Checked ahead of rootHandler/packageHandler/fallback
        // so they resolve to an explicit passthrough rather than falling
        // into ProxyDownloadSlice's "doesn't match download pattern, proxy
        // to remote verbatim" catch-all.
        if (AVAILABLE_PACKAGES.matcher(path).matches() || LIST_JSON.matcher(path).matches()) {
            return this.passthroughCatalogSurface(line, headers, body, auditCtx, user);
        }
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
                .field("log.source", "application")
                .log();
            return body.asBytesFuture()
                .thenCompose(ignored -> this.rootHandler.handle(line, user, auditCtx));
        }
        if (this.packageHandler != null && this.packageHandler.matches(path)) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Dispatching per-package metadata to cooldown handler")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            return body.asBytesFuture()
                .thenCompose(ignored -> this.packageHandler.handle(line, user, auditCtx));
        }
        return this.fallback.response(line, headers, body);
    }

    /**
     * HEAD support (WS4-composer.8): resolve exactly as GET across the
     * whole dispatch (root / per-package metadata / catalog surfaces /
     * dist download), then drop the body before returning so the client
     * sees the same status/headers without a body (RFC 9110 &sect;9.3.2).
     */
    private CompletableFuture<Response> headAsGet(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final RequestLine asGet = new RequestLine(RqMethod.GET, line.uri(), line.version());
        return this.response(asGet, headers, body).thenCompose(resp ->
            resp.body().asBytesFuture().thenApply(
                ignored -> new Response(resp.status(), resp.headers(), Content.EMPTY)
            )
        );
    }

    /**
     * Live passthrough for the catalog surfaces (WS4-composer.5/.6):
     * forward the request verbatim to the raw upstream and return its
     * response unmodified — these bodies carry no per-package download
     * URLs to rewrite (just names / an availability list), so there is
     * nothing for {@link MetadataUrlRewriter} to do. Audited as a
     * metadata-listing view ({@code artifact_resolution}), matching the
     * root/per-package surfaces; the {@code detail unavailable} variant
     * is used because a live passthrough has no cooldown-filter detail to
     * report (unlike the root/per-package handlers, which do their own
     * per-version filtering).
     */
    private CompletableFuture<Response> passthroughCatalogSurface(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final AuditContext auditCtx,
        final String user
    ) {
        final String path = line.uri().getPath();
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Live-passthrough catalog surface request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("url.path", path)
            .field("log.source", "application")
            .log();
        // GET requests carry no meaningful body; drain it here (per the
        // "always consume Content" contract) and forward Content.EMPTY
        // downstream, matching the rootHandler/packageHandler dispatch
        // idiom above rather than threading the original body through.
        return body.asBytesFuture().thenCompose(
            ignored -> this.rawRemote.response(line, headers, Content.EMPTY)
        ).thenApply(response -> {
            AuditLogger.resolutionDetailUnknown(
                auditCtx, this.rtype, this.rname, path, user, "live-passthrough"
            );
            return response;
        });
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
