/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.cache.CachedArtifactMetadataStore;
import com.artipie.http.cache.NegativeCache;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPM proxy slice with negative and metadata caching.
 * Wraps NpmProxySlice to add caching layer that prevents repeated
 * 404 requests and caches package metadata.
 *
 * @since 1.0
 */
public final class CachedNpmProxySlice implements Slice {

    /**
     * Origin slice (NpmProxySlice).
     */
    private final Slice origin;

    /**
     * Negative cache for 404 responses.
     */
    private final NegativeCache negativeCache;

    /**
     * Metadata store for cached responses.
     */
    private final Optional<CachedArtifactMetadataStore> metadata;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Upstream URL.
     */
    private final String upstreamUrl;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * In-flight requests map for deduplication.
     * Maps request key to a future that completes with BUFFERED response data.
     * This prevents thundering herd while allowing each caller to get a fresh
     * Content instance (avoiding OneTimePublisher multiple subscription errors).
     */
    private final Map<Key, CompletableFuture<BufferedResponse>> inFlight;

    /**
     * Ctor with default caching (24h TTL, enabled).
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage
    ) {
        this(origin, storage, Duration.ofHours(24), true, "default", "unknown", "npm");
    }

    /**
     * Ctor with custom caching parameters.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(origin, storage, negativeCacheTtl, negativeCacheEnabled, "default", "unknown", "npm");
    }

    /**
     * Ctor with custom caching parameters and repository name.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     * @param repoName Repository name for cache key isolation
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled,
        final String repoName
    ) {
        this(origin, storage, negativeCacheTtl, negativeCacheEnabled, repoName, "unknown", "npm");
    }

    /**
     * Ctor with full parameters including upstream URL.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     * @param repoName Repository name for cache key isolation
     * @param upstreamUrl Upstream URL
     * @param repoType Repository type
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled,
        final String repoName,
        final String upstreamUrl,
        final String repoType
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
        this.repoType = repoType;
        this.negativeCache = new NegativeCache(
            negativeCacheTtl,
            negativeCacheEnabled,
            50_000,  // default max size
            null,     // use global Valkey config
            repoName  // CRITICAL: Include repo name
        );
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
        this.inFlight = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        
        // Skip caching for special npm endpoints
        if (this.isSpecialEndpoint(path)) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: bypassing cache for special endpoint")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();
            return this.origin.response(line, headers, body);
        }

        final Key key = new KeyFromPath(path);

        // Check negative cache first (404s)
        if (this.negativeCache.isNotFound(key)) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM package cached as 404 (negative cache hit)")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
        
        // Check metadata cache for tarballs and package.json
        if (this.metadata.isPresent() && this.isCacheable(path)) {
            return this.serveCached(line, headers, body, key);
        }
        
        // Fetch from origin and cache result
        return this.fetchAndCache(line, headers, body, key);
    }

    /**
     * Checks if path is a special endpoint that shouldn't be cached.
     * @param path Request path
     * @return True if path is a special endpoint (whoami, security, search, user, auth)
     */
    private boolean isSpecialEndpoint(final String path) {
        return path.startsWith("/-/whoami")
            || path.startsWith("/-/npm/v1/security/")
            || path.startsWith("/-/v1/search")
            || path.startsWith("/-/user/")
            || path.contains("/auth");
    }

    /**
     * Checks if path represents cacheable content.
     * @param path Request path
     * @return True if path is a tarball or package.json
     */
    private boolean isCacheable(final String path) {
        return path.endsWith(".tgz")
            || path.endsWith("/-/package.json")
            || (path.contains("/-/") && path.endsWith(".json"));
    }

    /**
     * Serves from metadata cache or fetches if not cached.
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> serveCached(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        return this.metadata.orElseThrow().load(key).thenCompose(meta -> {
            if (meta.isPresent()) {
                EcsLogger.debug("com.artipie.npm")
                    .message("NPM proxy: serving from metadata cache")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("package.name", key.string())
                    .log();
                // Metadata exists - serve cached with headers
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(meta.get().headers())
                        .build()
                );
            }
            // Cache miss - fetch from origin
            return this.fetchAndCache(line, headers, body, key);
        });
    }

    /**
     * Fetches from origin and caches the result with request deduplication.
     * <p>Response bodies are buffered so each caller gets a fresh Content instance,
     * preventing OneTimePublisher multiple subscription errors.</p>
     * @param line Request line
     * @param headers Request headers
     * @param body Request body
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        final CompletableFuture<BufferedResponse> pending = this.inFlight.get(key);
        if (pending != null) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: joining in-flight request")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return pending.thenApply(BufferedResponse::toFreshResponse);
        }

        final long startTime = System.currentTimeMillis();
        final CompletableFuture<BufferedResponse> newRequest = new CompletableFuture<>();
        
        final CompletableFuture<BufferedResponse> existing = this.inFlight.putIfAbsent(key, newRequest);
        if (existing != null) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: lost race, joining other request")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return existing.thenApply(BufferedResponse::toFreshResponse);
        }

        EcsLogger.debug("com.artipie.npm")
            .message("NPM proxy: fetching upstream (new request)")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("package.name", key.string())
            .log();
        return this.origin.response(line, headers, body)
            .thenCompose(response -> {
                final long duration = System.currentTimeMillis() - startTime;
                if (response.status().code() == 404) {
                    EcsLogger.debug("com.artipie.npm")
                        .message("NPM proxy: caching 404")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .field("package.name", key.string())
                        .log();
                    this.negativeCache.cacheNotFound(key);
                    this.recordProxyMetric("not_found", duration);
                    final BufferedResponse buffered404 = new BufferedResponse(
                        response.status(), response.headers(), new byte[0]
                    );
                    newRequest.complete(buffered404);
                    return CompletableFuture.completedFuture(buffered404.toFreshResponse());
                }

                if (response.status().success()) {
                    this.recordProxyMetric("success", duration);
                    return response.body().asBytesFuture()
                        .thenApply(bodyBytes -> {
                            final BufferedResponse buffered = new BufferedResponse(
                                response.status(), response.headers(), bodyBytes
                            );
                            newRequest.complete(buffered);
                            return buffered.toFreshResponse();
                        })
                        .exceptionally(err -> {
                            EcsLogger.warn("com.artipie.npm")
                                .message("NPM proxy: body read failed")
                                .eventCategory("repository")
                                .eventAction("proxy_request")
                                .field("package.name", key.string())
                                .error(err)
                                .log();
                            final Response errorResp = ResponseBuilder.unavailable()
                                .textBody("Upstream read error - please retry")
                                .build();
                            final BufferedResponse bufferedErr = new BufferedResponse(
                                errorResp.status(), errorResp.headers(), new byte[0]
                            );
                            newRequest.complete(bufferedErr);
                            return errorResp;
                        });
                } else if (response.status().code() >= 500) {
                    this.recordProxyMetric("error", duration);
                    this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                } else {
                    this.recordProxyMetric("client_error", duration);
                }

                return response.body().asBytesFuture()
                    .thenApply(bodyBytes -> {
                        final BufferedResponse buffered = new BufferedResponse(
                            response.status(), response.headers(), bodyBytes
                        );
                        newRequest.complete(buffered);
                        return buffered.toFreshResponse();
                    })
                    .exceptionally(err -> {
                        final Response errorResp = ResponseBuilder.unavailable()
                            .textBody("Upstream read error")
                            .build();
                        final BufferedResponse bufferedErr = new BufferedResponse(
                            errorResp.status(), errorResp.headers(), new byte[0]
                        );
                        newRequest.complete(bufferedErr);
                        return errorResp;
                    });
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                EcsLogger.warn("com.artipie.npm")
                    .message("NPM proxy: upstream request failed")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .error(error)
                    .log();
                final Response errorResp = ResponseBuilder.unavailable()
                    .textBody("Upstream error - please retry")
                    .build();
                final BufferedResponse bufferedErr = new BufferedResponse(
                    errorResp.status(), errorResp.headers(), new byte[0]
                );
                newRequest.complete(bufferedErr);
                return errorResp;
            })
            .whenComplete((result, error) -> {
                this.inFlight.remove(key);
            });
    }

    /**
     * Records proxy request metric.
     * @param result Request result (success, not_found, error, etc.)
     * @param duration Request duration in milliseconds
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.repoName, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Records upstream error metric.
     * @param error The error that occurred
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.repoName, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Records metric safely, ignoring errors.
     * @param metric Metric recording action
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetric(final Runnable metric) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            // Ignore metric errors - don't fail requests
        }
    }

    /**
     * Buffered response data for request deduplication.
     * <p>Stores response status, headers, and body bytes so each caller
     * can receive a fresh Response with new Content instance.</p>
     */
    private static final class BufferedResponse {
        /**
         * Response status.
         */
        private final RsStatus status;

        /**
         * Response headers.
         */
        private final Headers headers;

        /**
         * Buffered response body bytes.
         */
        private final byte[] body;

        /**
         * Ctor.
         * @param status Response status
         * @param headers Response headers
         * @param body Buffered body bytes
         */
        BufferedResponse(final RsStatus status, final Headers headers, final byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        /**
         * Creates a fresh Response with new Content from buffered data.
         * @return New Response instance with fresh Content
         */
        Response toFreshResponse() {
            return ResponseBuilder.from(this.status)
                .headers(this.headers)
                .body(this.body.length > 0 ? new Content.From(this.body) : Content.EMPTY)
                .build();
        }
    }
}
