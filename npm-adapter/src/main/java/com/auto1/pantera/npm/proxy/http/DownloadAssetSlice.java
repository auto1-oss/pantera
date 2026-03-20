/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
import hu.akarnokd.rxjava2.interop.SingleInterop;

import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.cooldown.CooldownRequest;
import com.auto1.pantera.cooldown.CooldownResponses;
import com.auto1.pantera.cooldown.CooldownResult;
import com.auto1.pantera.cooldown.CooldownService;
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
                .eventCategory("repository")
                .eventAction("get_asset")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .error(cause)
                .log();
            
            // Check if it's an HTTP exception with a specific status
            if (cause instanceof com.auto1.pantera.http.ArtipieHttpException) {
                final com.auto1.pantera.http.ArtipieHttpException httpEx = 
                    (com.auto1.pantera.http.ArtipieHttpException) cause;
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
        // Use a non-blocking check that returns asset from storage if present.
        return this.npm.getAsset(tgz)
            .map(asset -> {
                // Asset found in storage cache - check if it's served from cache (not remote)
                // Since getAsset tries storage first, if we have it, serve immediately
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("Cache hit for asset, serving cached (offline-safe)")
                    .eventCategory("repository")
                    .eventAction("get_asset")
                    .eventOutcome("cache_hit")
                    .field("package.name", tgz)
                    .log();
                // Queue the proxy event
                this.packages.ifPresent(queue -> {
                    Long millis = null;
                    try {
                        final String lm = asset.meta().lastModified();
                        if (!Strings.isNullOrEmpty(lm)) {
                            millis = java.time.Instant.from(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm)).toEpochMilli();
                        }
                    } catch (final Exception ex) {
                        EcsLogger.debug("com.auto1.pantera.npm")
                            .message("Failed to parse asset lastModified for proxy event")
                            .error(ex)
                            .log();
                    }
                    queue.add(
                        new ProxyArtifactEvent(
                            new Key.From(tgz), this.repoName,
                            new Login(headers).getValue(),
                            java.util.Optional.ofNullable(millis)
                        )
                    );
                });
                String mime = asset.meta().contentType();
                if (Strings.isNullOrEmpty(mime)){
                    throw new IllegalStateException("Failed to get 'Content-Type'");
                }
                String lastModified = asset.meta().lastModified();
                if(Strings.isNullOrEmpty(lastModified)){
                    lastModified = new DateTimeNowStr().value();
                }
                return ResponseBuilder.ok()
                    .header(ContentType.mime(mime))
                    .header("Last-Modified", lastModified)
                    .body(asset.dataPublisher())
                    .build();
            })
            .toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture()
            .thenCompose(response -> {
                // If we got a 404 (not in storage), now we need to go to remote
                // At this point, we should evaluate cooldown first
                if (response.status().code() == 404) {
                    return this.evaluateCooldownAndFetch(tgz, headers);
                }
                // Asset was served from cache - return it
                return CompletableFuture.completedFuture(response);
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
                        .eventCategory("cooldown")
                        .eventAction("asset_blocked")
                        .field("package.name", req.artifact())
                        .field("package.version", req.version())
                        .log();
                    return CompletableFuture.completedFuture(
                        CooldownResponses.forbidden(block)
                    );
                }
                return this.serveAsset(tgz, headers);
            });
    }

    private CompletableFuture<Response> serveAsset(final String tgz, final Headers headers) {
        return this.npm.getAsset(tgz).map(
                asset -> {
                    this.packages.ifPresent(queue -> {
                        Long millis = null;
                        try {
                            final String lm = asset.meta().lastModified();
                            if (!Strings.isNullOrEmpty(lm)) {
                                millis = java.time.Instant.from(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm)).toEpochMilli();
                            }
                        } catch (final Exception ex) {
                            EcsLogger.debug("com.auto1.pantera.npm")
                                .message("Failed to parse asset lastModified for proxy event")
                                .error(ex)
                                .log();
                        }
                        queue.add(
                            new ProxyArtifactEvent(
                                new Key.From(tgz), this.repoName,
                                new Login(headers).getValue(),
                                java.util.Optional.ofNullable(millis)
                            )
                        );
                    });
                    return asset;
                })
            .map(
                asset -> {
                    String mime = asset.meta().contentType();
                    if (Strings.isNullOrEmpty(mime)){
                        throw new IllegalStateException("Failed to get 'Content-Type'");
                    }
                    String lastModified = asset.meta().lastModified();
                    if(Strings.isNullOrEmpty(lastModified)){
                        lastModified = new DateTimeNowStr().value();
                    }
                    // Stream content directly - no buffering needed.
                    // MicrometerSlice fix ensures response bodies aren't double-subscribed.
                    return ResponseBuilder.ok()
                        .header(ContentType.mime(mime))
                        .header("Last-Modified", lastModified)
                        .body(asset.dataPublisher())
                        .build();
                }
            )
            .toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture();
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
