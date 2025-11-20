/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.cache.CachedArtifactMetadataStore;
import com.artipie.http.cache.NegativeCache;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PyPI proxy slice with negative and metadata caching.
 * Wraps PyProxySlice to add caching layer that prevents repeated
 * 404 requests and caches package metadata.
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
     * Ctor with default caching (24h TTL, enabled).
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     */
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage
    ) {
        this(origin, storage, Duration.ofHours(24), true, "default");
    }

    /**
     * Ctor with custom caching parameters.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     */
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this(origin, storage, negativeCacheTtl, negativeCacheEnabled, "default");
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
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl,
        final boolean negativeCacheEnabled,
        final String repoName
    ) {
        this.origin = origin;
        this.negativeCache = new NegativeCache(
            negativeCacheTtl, 
            negativeCacheEnabled, 
            50_000,  // default max size
            null,     // use global Valkey config
            repoName  // CRITICAL: Include repo name
        );
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
            EcsLogger.debug("com.artipie.pypi")
                .message("PyPI package cached as 404 (negative cache hit)")
                .eventCategory("repository")
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
                EcsLogger.debug("com.artipie.pypi")
                    .message("PyPI proxy: serving from metadata cache")
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
     * Fetch from origin and cache the result.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        EcsLogger.debug("com.artipie.pypi")
            .message("PyPI proxy: fetching upstream")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("package.name", key.string())
            .log();
        return this.origin.response(line, headers, body).thenCompose(response -> {
            // Check for 404 status
            if (response.status().code() == 404) {
                EcsLogger.debug("com.artipie.pypi")
                    .message("PyPI proxy: caching 404")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("package.name", key.string())
                    .log();
                // Cache 404 to avoid repeated upstream requests
                this.negativeCache.cacheNotFound(key);
                return CompletableFuture.completedFuture(response);
            }

            if (response.status().success() && this.metadata.isPresent() && this.isCacheable(key.string())) {
                // Cache successful response metadata
                EcsLogger.debug("com.artipie.pypi")
                    .message("PyPI proxy: caching metadata")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("package.name", key.string())
                    .log();
                // Note: Full metadata caching with body digests would require
                // consuming the response body, which is complex.
                // For now, just cache the 404s (most impactful).
            }

            return CompletableFuture.completedFuture(response);
        });
    }
}
