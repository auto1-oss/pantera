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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Login;
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
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            // URL-decode path to handle scoped packages like @authn8%2fmcp-server -> @authn8/mcp-server
            final String rawPath = this.path.value(line.uri().getPath());
            final String tgz = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
            // This ensures offline mode works - serve cached content even when upstream is down
            return this.checkCacheFirst(tgz, rqheaders);
        }).exceptionally(error -> {
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
                .log();
            
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
    private CompletableFuture<Response> checkCacheFirst(final String tgz, final Headers headers) {
        // NpmProxy.getAsset checks storage first internally, but we need to check BEFORE
        // calling cooldown.evaluate() which may make network calls.
        // Convert RxJava Maybe at the NpmProxy boundary to CompletionStage.
        return this.npm.getAssetAsync(tgz)
            .thenCompose(optAsset -> {
                if (optAsset.isEmpty()) {
                    // Cache miss — evaluate cooldown then fetch from upstream
                    return this.evaluateCooldownAndFetch(tgz, headers);
                }
                final var asset = optAsset.get();
                // Asset found in storage cache — serve immediately (offline-safe)
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("Cache hit for asset, serving cached (offline-safe)")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", tgz)
                    .log();
                // Queue the proxy event — failures MUST NOT escape the serve path.
                this.enqueueProxyEvent(tgz, headers, asset);
                String mime = asset.meta().contentType();
                if (Strings.isNullOrEmpty(mime)) {
                    throw new IllegalStateException("Failed to get 'Content-Type'");
                }
                String lastModified = asset.meta().lastModified();
                if (Strings.isNullOrEmpty(lastModified)) {
                    lastModified = new DateTimeNowStr().value();
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header(ContentType.mime(mime))
                        .header("Last-Modified", lastModified)
                        .body(asset.dataPublisher())
                        .build()
                );
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
        final Headers headers
    ) {
        final Optional<CooldownRequest> request = this.cooldownRequest(tgz, headers);
        if (request.isEmpty()) {
            return this.serveAsset(tgz, headers);
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
                        .log();
                    return CompletableFuture.completedFuture(
                        CooldownResponseRegistry.instance()
                            .getOrThrow(this.repoType)
                            .forbidden(block)
                    );
                }
                return this.serveAsset(tgz, headers);
            });
    }

    private CompletableFuture<Response> serveAsset(final String tgz, final Headers headers) {
        // Convert RxJava Maybe at the NpmProxy boundary to CompletionStage.
        return this.npm.getAssetAsync(tgz)
            .thenApply(optAsset -> {
                if (optAsset.isEmpty()) {
                    return ResponseBuilder.notFound().build();
                }
                final var asset = optAsset.get();
                // Enqueue failures (bounded queue full, lambda exception, ...)
                // MUST NOT escape the serve path — wrap the whole body.
                this.enqueueProxyEvent(tgz, headers, asset);
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
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void enqueueProxyEvent(
        final String tgz,
        final Headers headers,
        final com.auto1.pantera.npm.proxy.model.NpmAsset asset
    ) {
        this.packages.ifPresent(queue -> {
            try {
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
                    .log();
            }
        });
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
