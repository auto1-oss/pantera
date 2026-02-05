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

import com.artipie.cache.DistributedInFlight;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
     * Distributed in-flight tracker for request deduplication.
     * Uses Pub/Sub for instant notification across cluster nodes.
     */
    private final DistributedInFlight inFlight;

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
        // Distributed in-flight with Pub/Sub for cluster-wide deduplication
        this.inFlight = new DistributedInFlight(repoType + ":" + repoName);
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
     * Fetches from origin with distributed request deduplication.
     * Uses DistributedInFlight with Pub/Sub for cluster-wide coordination.
     * Only one node fetches from upstream; others wait and read from cache.
     *
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
        return this.inFlight.tryAcquire(key.string())
            .thenCompose(result -> {
                if (result.isLeader()) {
                    // We are the leader - fetch from upstream
                    EcsLogger.debug("com.artipie.npm")
                        .message("NPM proxy: fetching upstream (leader)")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .field("repository.name", this.repoName)
                        .field("package.name", key.string())
                        .field("url.original", this.upstreamUrl)
                        .log();
                    return this.doFetchAndCache(line, headers, body, key)
                        .whenComplete((response, error) -> {
                            // Signal completion to waiters via Pub/Sub
                            final boolean success = error == null && response != null
                                && response.status().success();
                            result.complete(success);
                        });
                } else {
                    // We are a waiter - wait for leader via Pub/Sub, then read from cache
                    EcsLogger.debug("com.artipie.npm")
                        .message("NPM proxy: waiting for leader (Pub/Sub)")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .field("repository.name", this.repoName)
                        .field("package.name", key.string())
                        .log();
                    return result.waitForLeader()
                        .thenCompose(success -> this.handleWaiterResult(success, line, headers, key));
                }
            });
    }

    /**
     * Internal method that performs the actual fetch from upstream.
     * Should only be called through fetchAndCache for deduplication.
     */
    private CompletableFuture<Response> doFetchAndCache(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        final long startTime = System.currentTimeMillis();
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
                    return ResponseBuilder.notFound().build();
                }

                if (response.status().success()) {
                    this.recordProxyMetric("success", duration);
                    return response;
                }

                // Error responses (4xx other than 404, 5xx)
                if (response.status().code() >= 500) {
                    this.recordProxyMetric("error", duration);
                    this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                } else {
                    this.recordProxyMetric("client_error", duration);
                }
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
                    .field("service.target.url.original", this.upstreamUrl)
                    .error(error)
                    .log();
                return ResponseBuilder.unavailable()
                    .textBody("Upstream error - please retry")
                    .build();
            });
    }

    /**
     * Handle result for a waiter based on leader completion signal.
     * @param leaderSuccess True if leader succeeded (content should be in cache)
     * @param line Original request line
     * @param headers Original request headers
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> handleWaiterResult(
        final boolean leaderSuccess,
        final RequestLine line,
        final Headers headers,
        final Key key
    ) {
        if (leaderSuccess) {
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
        } else {
            // Leader failed - return 503 for retry
            EcsLogger.debug("com.artipie.npm")
                .message("NPM proxy: waiter received failure signal, returning 503")
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

}
