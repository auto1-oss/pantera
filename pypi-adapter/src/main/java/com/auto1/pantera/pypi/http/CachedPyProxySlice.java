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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PyPI proxy slice with negative and metadata caching.
 * Wraps PyProxySlice to add caching layer that prevents repeated
 * 404 requests and caches package metadata.
 *
 * <p>TODO(WI-post-07): wire {@link com.auto1.pantera.http.cache.ProxyCacheWriter}
 * here so the PyPI adapter inherits the same primary+sidecar integrity
 * guarantee the Maven adapter received in WI-07 (§9.5).
 *
 * @since 1.0
 */
public final class CachedPyProxySlice implements Slice {

    /**
     * Origin slice (PyProxySlice).
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
     * Ctor with default caching (24h TTL, enabled).
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     */
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage
    ) {
        this(origin, storage, Duration.ofHours(24), true, "default", "unknown", "pypi");
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
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(origin, storage, negativeCacheTtl, negativeCacheEnabled, "default", "unknown", "pypi");
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
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled,
        final String repoName
    ) {
        this(origin, storage, negativeCacheTtl, negativeCacheEnabled, repoName, "unknown", "pypi");
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
    public CachedPyProxySlice(
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
        // TTL, maxSize, and Valkey settings come from global config (caches.negative in pantera.yml)
        this.negativeCache = new NegativeCache(repoType, repoName);
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        final Key key = new KeyFromPath(path);
        
        // Check negative cache first (404s)
        if (this.negativeCache.isNotFound(key)) {
            EcsLogger.debug("com.auto1.pantera.pypi")
                .message("PyPI package cached as 404 (negative cache hit)")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        // Check metadata cache for wheels and index pages
        if (this.metadata.isPresent() && this.isCacheable(path)) {
            return this.serveCached(line, headers, body, key);
        }

        // Fetch from origin and cache result
        return this.fetchAndCache(line, headers, body, key);
    }

    /**
     * Check if path represents cacheable content (wheels, sdists, index HTML).
     */
    private boolean isCacheable(final String path) {
        return path.endsWith(".whl")
            || path.endsWith(".tar.gz")
            || path.endsWith(".zip")
            || path.contains("/simple/");
    }

    /**
     * Serve from cache or fetch if not cached.
     */
    private CompletableFuture<Response> serveCached(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        return this.metadata.orElseThrow().load(key).thenCompose(meta -> {
            if (meta.isPresent()) {
                EcsLogger.debug("com.auto1.pantera.pypi")
                    .message("PyPI proxy: serving from metadata cache")
                    .eventCategory("web")
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
     * Fetch from origin and cache the result.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        final long startTime = System.currentTimeMillis();
        EcsLogger.debug("com.auto1.pantera.pypi")
            .message("PyPI proxy: fetching upstream")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("package.name", key.string())
            .log();
        return this.origin.response(line, headers, body)
            .thenCompose(response -> {
                final long duration = System.currentTimeMillis() - startTime;
                // Check for 404 status
                if (response.status().code() == 404) {
                    EcsLogger.debug("com.auto1.pantera.pypi")
                        .message("PyPI proxy: caching 404")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .field("package.name", key.string())
                        .log();
                    // Cache 404 to avoid repeated upstream requests
                    this.negativeCache.cacheNotFound(key);
                    this.recordProxyMetric("not_found", duration);
                    return CompletableFuture.completedFuture(response);
                }

                if (response.status().success()) {
                    this.recordProxyMetric("success", duration);
                    if (this.metadata.isPresent() && this.isCacheable(key.string())) {
                        // Cache successful response metadata
                        EcsLogger.debug("com.auto1.pantera.pypi")
                            .message("PyPI proxy: caching metadata")
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .field("package.name", key.string())
                            .log();
                        // Note: Full metadata caching with body digests would require
                        // consuming the response body, which is complex.
                        // For now, just cache the 404s (most impactful).
                    }
                } else if (response.status().code() >= 500) {
                    this.recordProxyMetric("error", duration);
                    this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                } else {
                    this.recordProxyMetric("client_error", duration);
                }

                return CompletableFuture.completedFuture(response);
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                throw new java.util.concurrent.CompletionException(error);
            });
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.repoName, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.repoName, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.pypi")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
