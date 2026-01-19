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
 * <p>Uses signal-based request deduplication: concurrent requests for the same
 * package wait for the first request to complete, then read from NpmProxy's
 * storage cache. This eliminates memory buffering while maintaining full
 * deduplication.</p>
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
     * In-flight requests map for signal-based deduplication.
     * Maps request key to a future that completes with a FetchResult signal.
     * Waiters act on the signal: SUCCESS means read from storage cache,
     * NOT_FOUND means return 404, ERROR means retry.
     * This eliminates memory buffering while maintaining full deduplication.
     */
    private final Map<Key, CompletableFuture<FetchResult>> inFlight;

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
     * @param negativeCacheTtl TTL for negative cache (ignored - uses unified NegativeCacheConfig)
     * @param negativeCacheEnabled Whether negative caching is enabled (ignored - uses unified NegativeCacheConfig)
     * @deprecated Use constructor without negative cache params - negative cache now uses unified NegativeCacheConfig
     */
    @Deprecated
    @SuppressWarnings("PMD.UnusedFormalParameter")
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
     * @param negativeCacheTtl TTL for negative cache (ignored - uses unified NegativeCacheConfig)
     * @param negativeCacheEnabled Whether negative caching is enabled (ignored - uses unified NegativeCacheConfig)
     * @param repoName Repository name for cache key isolation
     * @deprecated Use constructor without negative cache params - negative cache now uses unified NegativeCacheConfig
     */
    @Deprecated
    @SuppressWarnings("PMD.UnusedFormalParameter")
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
     * @param negativeCacheTtl TTL for negative cache (ignored - uses unified NegativeCacheConfig)
     * @param negativeCacheEnabled Whether negative caching is enabled (ignored - uses unified NegativeCacheConfig)
     * @param repoName Repository name for cache key isolation
     * @param upstreamUrl Upstream URL
     * @param repoType Repository type
     * @deprecated Use constructor without negative cache params - negative cache now uses unified NegativeCacheConfig
     */
    @Deprecated
    @SuppressWarnings("PMD.UnusedFormalParameter")
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
        // Use unified NegativeCacheConfig for consistent settings across all adapters
        // TTL, maxSize, and Valkey settings come from global config (caches.negative in artipie.yml)
        this.negativeCache = new NegativeCache(repoType, repoName);
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
                .field("repository.name", this.repoName)
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
                .field("repository.name", this.repoName)
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
                    .field("repository.name", this.repoName)
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
     * Fetches from origin with signal-based request deduplication.
     * <p>First request fetches from origin (which saves to NpmProxy's storage cache).
     * Concurrent requests wait for a signal, then fetch from origin again - which
     * will be served from storage cache. This eliminates memory buffering while
     * maintaining full deduplication.</p>
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
        // Check for existing in-flight request
        final CompletableFuture<FetchResult> pending = this.inFlight.get(key);
        if (pending != null) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: joining in-flight request (signal-based)")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("repository.name", this.repoName)
                .field("package.name", key.string())
                .log();
            // Wait for signal, then act accordingly
            return pending.thenCompose(result -> 
                this.handleWaiterResult(result, line, headers, key)
            );
        }

        final long startTime = System.currentTimeMillis();
        final CompletableFuture<FetchResult> newRequest = new CompletableFuture<>();
        
        // Try to register as first request
        final CompletableFuture<FetchResult> existing = this.inFlight.putIfAbsent(key, newRequest);
        if (existing != null) {
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: lost race, joining other request (signal-based)")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("repository.name", this.repoName)
                .field("package.name", key.string())
                .log();
            return existing.thenCompose(result -> 
                this.handleWaiterResult(result, line, headers, key)
            );
        }

        EcsLogger.debug("com.artipie.npm")
            .message("NPM proxy: fetching upstream (first request)")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("repository.name", this.repoName)
            .field("package.name", key.string())
            .field("url.original", this.upstreamUrl)
            .log();
        
        // First request: fetch from origin
        return this.origin.response(line, headers, body)
            .thenApply(response -> {
                final long duration = System.currentTimeMillis() - startTime;
                
                if (response.status().code() == 404) {
                    EcsLogger.debug("com.artipie.npm")
                        .message("NPM proxy: caching 404")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .eventOutcome("not_found")
                        .field("repository.name", this.repoName)
                        .field("package.name", key.string())
                        .log();
                    this.negativeCache.cacheNotFound(key);
                    this.recordProxyMetric("not_found", duration);
                    // Signal waiters: NOT_FOUND
                    newRequest.complete(FetchResult.NOT_FOUND);
                    return ResponseBuilder.notFound().build();
                }

                if (response.status().success()) {
                    this.recordProxyMetric("success", duration);
                    // Signal waiters: SUCCESS - they will read from storage cache
                    newRequest.complete(FetchResult.SUCCESS);
                    // First request returns the streaming response directly
                    return response;
                }
                
                // Error responses (4xx other than 404, 5xx)
                if (response.status().code() >= 500) {
                    this.recordProxyMetric("error", duration);
                    this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                } else {
                    this.recordProxyMetric("client_error", duration);
                }
                // Signal waiters: ERROR - they should retry
                newRequest.complete(FetchResult.ERROR);
                return response;
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                EcsLogger.warn("com.artipie.npm")
                    .message("NPM proxy: upstream request failed")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .field("url.upstream", this.upstreamUrl)
                    .error(error)
                    .log();
                // Signal waiters: ERROR
                newRequest.complete(FetchResult.ERROR);
                return ResponseBuilder.unavailable()
                    .textBody("Upstream error - please retry")
                    .build();
            })
            .whenComplete((result, error) -> {
                this.inFlight.remove(key);
            });
    }

    /**
     * Handle result for a waiter based on the signal from the first request.
     * @param result Fetch result signal
     * @param line Original request line
     * @param headers Original request headers
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> handleWaiterResult(
        final FetchResult result,
        final RequestLine line,
        final Headers headers,
        final Key key
    ) {
        switch (result) {
            case SUCCESS:
                // Data is now in NpmProxy's storage cache - fetch from origin
                // which will serve from cache (no upstream request)
                EcsLogger.debug("com.artipie.npm")
                    .message("NPM proxy: waiter fetching from cache")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .log();
                return this.origin.response(line, headers, Content.EMPTY);
                
            case NOT_FOUND:
                // 404 already cached by first request
                EcsLogger.debug("com.artipie.npm")
                    .message("NPM proxy: waiter received NOT_FOUND signal")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
                
            case ERROR:
            default:
                // First request failed - waiter should retry (which may go to upstream)
                EcsLogger.debug("com.artipie.npm")
                    .message("NPM proxy: waiter received ERROR signal, returning 503")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unavailable()
                        .textBody("Upstream temporarily unavailable - please retry")
                        .build()
                );
        }
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
     * Result signal for in-flight request deduplication.
     * <p>Signals the outcome of the first request to waiting requests:</p>
     * <ul>
     *   <li>SUCCESS - Data saved to storage, waiters should read from cache</li>
     *   <li>NOT_FOUND - 404 from upstream, already cached in negative cache</li>
     *   <li>ERROR - Transient error, waiters should retry or return 503</li>
     * </ul>
     */
    private enum FetchResult {
        /**
         * Success - data is now in storage cache.
         */
        SUCCESS,
        
        /**
         * Not found - 404 cached in negative cache.
         */
        NOT_FOUND,
        
        /**
         * Error - transient failure, retry may help.
         */
        ERROR
    }
}
