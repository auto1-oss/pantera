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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.misc.DateTimeNowStr;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.google.common.base.Strings;

import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.log.EcsLogger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import org.slf4j.MDC;

/**
 * HTTP slice for download asset requests.
 */
public final class DownloadAssetSlice implements Slice {
    /**
     * NPM Proxy facade.
     */
    private final NpmProxy npm;

    /**
     * Asset path helper.
     */
    private final AssetPath path;

    /**
     * Queue with packages and owner names.
     */
    private final Optional<Queue<ProxyArtifactEvent>> packages;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * @param npm NPM Proxy facade
     * @param path Asset path helper
     * @param packages Queue with proxy packages and owner
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    public DownloadAssetSlice(final NpmProxy npm, final AssetPath path,
        final Optional<Queue<ProxyArtifactEvent>> packages, final String repoName,
        final String repoType, final CooldownService cooldown, final CooldownInspector inspector) {
        this.npm = npm;
        this.path = path;
        this.packages = packages;
        this.repoName = repoName;
        this.repoType = repoType;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line,
                                                final Headers rqheaders,
                                                final Content body) {
        // Phase 10.5 profiler — total npm tarball wall time per request.
        final long entryNs = System.nanoTime();
        // Captured before any async hop below so the access-audit record
        // reflects THIS request's correlation context, not whatever (or
        // nothing) is bound to the worker thread that eventually runs the
        // storage/network continuations.
        final AuditContext ctx = this.captureAuditContext(rqheaders);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            // URL-decode path to handle scoped packages like @authn8%2fmcp-server -> @authn8/mcp-server
            final String rawPath = this.path.value(line.uri().getPath());
            final String tgz = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
            // This ensures offline mode works - serve cached content even when upstream is down
            return this.checkCacheFirst(tgz, rqheaders, ctx);
        }).whenComplete((r, e) -> recordPhase("asset_total", entryNs))
        .exceptionally(error -> {
            // CRITICAL: Convert exceptions to proper HTTP responses to prevent
            // "Parse Error: Expected HTTP/" errors in npm client.
            final Throwable cause = unwrapException(error);
            EcsLogger.error("com.auto1.pantera.npm")
                .message("Error processing asset request")
                .eventCategory("web")
                .eventAction("get_asset")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .error(cause)
                .field("log.source", "application")
                .log();
            
            // A breaker fast-fail keeps its marker so the group resolver
            // can skip this member without convicting it.
            if (cause instanceof com.auto1.pantera.http.UpstreamCircuitOpenException circuit) {
                final ResponseBuilder rb = ResponseBuilder
                    .from(com.auto1.pantera.http.RsStatus.byCode(502))
                    .header(com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER, "true")
                    .jsonBody("{\"error\":\"Upstream circuit breaker is open\"}");
                if (circuit.retryAfterSeconds() > 0) {
                    rb.header("Retry-After", Long.toString(circuit.retryAfterSeconds()));
                }
                return rb.build();
            }
            // Check if it's an HTTP exception with a specific status
            if (cause instanceof com.auto1.pantera.http.PanteraHttpException) {
                final com.auto1.pantera.http.PanteraHttpException httpEx = 
                    (com.auto1.pantera.http.PanteraHttpException) cause;
                return ResponseBuilder.from(httpEx.status())
                    .jsonBody(String.format(
                        "{\"error\":\"%s\"}",
                        httpEx.getMessage() != null ? httpEx.getMessage() : "Upstream error"
                    ))
                    .build();
            }
            
            // Generic 502 Bad Gateway for upstream errors
            return ResponseBuilder.from(com.auto1.pantera.http.RsStatus.byCode(502))
                .jsonBody(String.format(
                    "{\"error\":\"Upstream error: %s\"}",
                    cause.getMessage() != null ? cause.getMessage() : "Unknown error"
                ))
                .build();
        });
    }
    
    /**
     * Unwrap CompletionException to get the root cause.
     */
    private static Throwable unwrapException(final Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Check storage cache first before evaluating cooldown. This ensures offline mode works -
     * cached content is served even when upstream/network is unavailable.
     *
     * @param tgz Asset path (tarball)
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> checkCacheFirst(
        final String tgz, final Headers headers, final AuditContext ctx
    ) {
        // Pure storage existence probe — NOT the combined check-then-fetch
        // NpmProxy.getAsset/getAssetAsync, whose Maybe resolves present for
        // ANY asset that exists upstream (it fetches-and-saves on miss
        // internally). Using that combined result to gate "was this a
        // cache hit" would misclassify every fresh fetch as a hit AND
        // would evaluate cooldown only after the artifact was already
        // fetched and saved. This probe is what actually distinguishes the
        // two cases before either cooldown or the publish decision.
        final long cacheCheckNs = System.nanoTime();
        return this.npm.hasAssetInStorageAsync(tgz)
            .whenComplete((r, e) -> recordPhase("asset_cache_check", cacheCheckNs))
            .thenCompose(cached -> {
                if (!cached) {
                    // Cache miss — evaluate cooldown then fetch from upstream
                    return this.evaluateCooldownAndFetch(tgz, headers, ctx);
                }
                // Genuine cache hit — serve immediately (offline-safe). The
                // artifact was already published to the DB the first time
                // it was cached — this is a read, not a publish. No
                // ProxyArtifactEvent here; audit as access instead.
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("Cache hit for asset, serving cached (offline-safe)")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", tgz)
                    .field("log.source", "application")
                    .log();
                this.auditAccess(ctx, tgz, headers, 0L, AuditLogger.OUTCOME_SUCCESS, null);
                return this.npm.getAssetAsync(tgz).thenApply(optAsset -> {
                    final var asset = optAsset.orElseThrow();
                    String mime = asset.meta().contentType();
                    if (Strings.isNullOrEmpty(mime)) {
                        throw new IllegalStateException("Failed to get 'Content-Type'");
                    }
                    String lastModified = asset.meta().lastModified();
                    if (Strings.isNullOrEmpty(lastModified)) {
                        lastModified = new DateTimeNowStr().value();
                    }
                    return ResponseBuilder.ok()
                        .header(ContentType.mime(mime))
                        .header("Last-Modified", lastModified)
                        .body(asset.dataPublisher())
                        .build();
                });
            });
    }

    /**
     * Evaluate cooldown (if applicable) then fetch from upstream.
     * Only called when cache miss - requires network access.
     *
     * @param tgz Asset path
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> evaluateCooldownAndFetch(
        final String tgz,
        final Headers headers,
        final AuditContext ctx
    ) {
        final Optional<CooldownRequest> request = this.cooldownRequest(tgz, headers);
        if (request.isEmpty()) {
            return this.serveAsset(tgz, headers, ctx);
        }
        final CooldownRequest req = request.get();
        return this.cooldown.evaluate(req, this.inspector)
            .thenCompose(result -> {
                if (result.blocked()) {
                    final var block = result.block().orElseThrow();
                    EcsLogger.info("com.auto1.pantera.npm")
                        .message(String.format(
                            "Asset download blocked by cooldown: reason=%s, blockedUntil=%s",
                            block.reason(), block.blockedUntil()))
                        .eventCategory("database")
                        .eventAction("asset_blocked")
                        .field("package.name", req.artifact())
                        .field("package.version", req.version())
                        .field("log.source", "application")
                        .log();
                    this.auditAccess(
                        ctx, tgz, headers, 0L,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_COOLDOWN_ACTIVE
                    );
                    return CompletableFuture.completedFuture(
                        CooldownResponseRegistry.instance()
                            .getOrThrow(this.repoType)
                            .forbidden(block)
                    );
                }
                return this.serveAsset(tgz, headers, ctx);
            });
    }

    private CompletableFuture<Response> serveAsset(
        final String tgz, final Headers headers, final AuditContext ctx
    ) {
        // Convert RxJava Maybe at the NpmProxy boundary to CompletionStage.
        // Phase 10.5: this call drives upstream fetch + storage save when missing.
        final long upstreamNs = System.nanoTime();
        return this.npm.getAssetAsync(tgz)
            .whenComplete((r, e) -> recordPhase("asset_upstream_fetch_and_save", upstreamNs))
            .thenApply(optAsset -> {
                if (optAsset.isEmpty()) {
                    this.auditAccess(
                        ctx, tgz, headers, 0L,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                    );
                    return ResponseBuilder.notFound().build();
                }
                final var asset = optAsset.get();
                // Genuine cache miss + successful upstream fetch — the only
                // branch that should publish. Enqueue failures (bounded queue
                // full, lambda exception, ...) MUST NOT escape the serve
                // path — wrap the whole body.
                this.enqueueProxyEvent(tgz, headers, asset);
                this.auditAccess(ctx, tgz, headers, 0L, AuditLogger.OUTCOME_SUCCESS, null);
                String mime = asset.meta().contentType();
                if (Strings.isNullOrEmpty(mime)) {
                    throw new IllegalStateException("Failed to get 'Content-Type'");
                }
                String lastModified = asset.meta().lastModified();
                if (Strings.isNullOrEmpty(lastModified)) {
                    lastModified = new DateTimeNowStr().value();
                }
                // Stream content directly - no buffering needed.
                return ResponseBuilder.ok()
                    .header(ContentType.mime(mime))
                    .header("Last-Modified", lastModified)
                    .body(asset.dataPublisher())
                    .build();
            });
    }

    /**
     * Enqueue a proxy artifact event for the given asset.
     * Failures (bounded queue full, parse errors) are swallowed
     * so the serve path is never affected.
     *
     * @param tgz Asset path
     * @param headers Request headers
     * @param asset The resolved asset
     */
    private void enqueueProxyEvent(
        final String tgz,
        final Headers headers,
        final com.auto1.pantera.npm.proxy.model.NpmAsset asset
    ) {
        this.packages.ifPresent(queue -> {
            try {
                // Restore MDC on whatever thread this runs on (the upstream
                // fetch + storage save continuation may not be the request
                // thread) so the ProxyArtifactEvent ctor below auto-captures
                // THIS request's trace.id/client.ip instead of null or a
                // stale leftover value.
                RequestContextHeaders.bindToMdc(headers);
                Long millis = null;
                try {
                    final String lm = asset.meta().lastModified();
                    if (!Strings.isNullOrEmpty(lm)) {
                        millis = java.time.Instant.from(
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm)
                        ).toEpochMilli();
                    }
                } catch (final Exception ex) {
                    EcsLogger.debug("com.auto1.pantera.npm")
                        .message("Failed to parse asset lastModified for proxy event")
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                }
                final ProxyArtifactEvent event = new ProxyArtifactEvent(
                    new Key.From(tgz), this.repoName,
                    new Login(headers).getValue(),
                    java.util.Optional.ofNullable(millis)
                );
                if (!queue.offer(event)) {
                    com.auto1.pantera.metrics.EventsQueueMetrics
                        .recordDropped(this.repoName);
                }
            } catch (final Throwable t) {
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("Failed to enqueue proxy event; serve path unaffected")
                    .eventCategory("process")
                    .eventAction("queue_enqueue")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .error(t)
                    .field("log.source", "application")
                    .log();
            }
        });
    }

    /**
     * Phase 10.5 profiler — emit per-phase histogram tagged by repo so the
     * npm cold-cache wall can be decomposed without bringing
     * {@link com.auto1.pantera.http.cache.BaseCachedProxySlice} into the
     * structurally-different npm path.
     */
    private void recordPhase(final String phase, final long startNs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordProxyPhaseDuration(this.repoName, phase, System.nanoTime() - startNs);
        }
    }

    /**
     * Build an {@link AuditContext} for the current request. Reads the
     * internal {@code X-Pantera-Ctx-*} headers into MDC first (a no-op if
     * already populated by {@code EcsLoggingSlice} on the request thread;
     * a real restore on a worker thread that never had it).
     *
     * @param headers Inbound request headers
     * @return Context carrying whatever trace id / client IP could be resolved
     */
    private AuditContext captureAuditContext(final Headers headers) {
        RequestContextHeaders.bindToMdc(headers);
        return new AuditContext(MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP));
    }

    /**
     * Emit an {@link AuditLogger#access} event for a tarball request,
     * deriving artifact name/version from the same parser {@link
     * #cooldownRequest} uses; falls back to the raw asset path when the
     * path doesn't parse as a package/version tarball.
     */
    private void auditAccess(
        final AuditContext ctx, final String tgz, final Headers headers,
        final long size, final String outcome, final String reason
    ) {
        final Optional<CooldownRequest> parsed = this.cooldownRequest(tgz, headers);
        final String artifactName = parsed.map(CooldownRequest::artifact).orElse(tgz);
        final String version = parsed.map(CooldownRequest::version).orElse(null);
        AuditLogger.access(
            ctx, this.repoType, this.repoName, artifactName, version, size,
            new Login(headers).getValue(), outcome, reason
        );
    }

    private Optional<CooldownRequest> cooldownRequest(final String original, final Headers headers) {
        final String decoded = URLDecoder.decode(original, StandardCharsets.UTF_8);
        final int sep = decoded.indexOf("/-/");
        if (sep < 0) {
            return Optional.empty();
        }
        final String pkg = decoded.substring(0, sep);
        final String file = decoded.substring(decoded.lastIndexOf('/') + 1);
        if (!file.endsWith(".tgz")) {
            return Optional.empty();
        }
        final String base = file.substring(0, file.length() - 4);
        final int dash = base.lastIndexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        final String version = base.substring(dash + 1);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final String user = new Login(headers).getValue();
        return Optional.of(
            new CooldownRequest(
                this.repoType,
                this.repoName,
                pkg,
                version,
                user,
                Instant.now()
            )
        );
    }
}
