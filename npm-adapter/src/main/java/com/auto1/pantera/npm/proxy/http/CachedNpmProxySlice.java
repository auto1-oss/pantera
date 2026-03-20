/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.cache.DedupStrategy;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.RequestDeduplicator;
import com.auto1.pantera.http.cache.RequestDeduplicator.FetchSignal;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * NPM proxy slice with negative caching and signal-based request deduplication.
 * Wraps NpmProxySlice to add caching layer that prevents repeated
 * 404 requests and deduplicates concurrent requests.
 *
 * <p>Uses shared {@link RequestDeduplicator} with SIGNAL strategy: concurrent
 * requests for the same package wait for the first request to complete, then
 * fetch from NpmProxy's storage cache. This eliminates memory buffering while
 * maintaining full deduplication.</p>
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
     * Shared request deduplicator using SIGNAL strategy.
     */
    private final RequestDeduplicator deduplicator;

    /**
     * Ctor with default settings.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage
    ) {
        this(origin, storage, "default", "unknown", "npm");
    }

    /**
     * Ctor with full parameters.
     *
     * @param origin Origin slice
     * @param storage Storage for metadata cache (optional)
     * @param repoName Repository name for cache key isolation
     * @param upstreamUrl Upstream URL for metrics
     * @param repoType Repository type
     */
    public CachedNpmProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final String repoName,
        final String upstreamUrl,
        final String repoType
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
        this.repoType = repoType;
        this.negativeCache = new NegativeCache(repoType, repoName);
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
        this.deduplicator = new RequestDeduplicator(DedupStrategy.SIGNAL);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        // Skip caching for special npm endpoints
        if (isSpecialEndpoint(path)) {
            return this.origin.response(line, headers, body);
        }
        final Key key = new KeyFromPath(path);
        // Check negative cache first (404s)
        if (this.negativeCache.isNotFound(key)) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
        // Check metadata cache for tarballs and package.json
        if (this.metadata.isPresent() && isCacheable(path)) {
            return this.serveCached(line, headers, body, key);
        }
        // Fetch from origin with request deduplication
        return this.fetchWithDedup(line, headers, body, key);
    }

    /**
     * Checks if path is a special endpoint that shouldn't be cached.
     * @param path Request path
     * @return True if path is a special endpoint
     */
    private static boolean isSpecialEndpoint(final String path) {
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
    private static boolean isCacheable(final String path) {
        return path.endsWith(".tgz")
            || path.endsWith("/-/package.json")
            || (path.contains("/-/") && path.endsWith(".json"));
    }

    /**
     * Serves from metadata cache or fetches if not cached.
     */
    private CompletableFuture<Response> serveCached(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        return this.metadata.orElseThrow().load(key).thenCompose(meta -> {
            if (meta.isPresent()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(meta.get().headers())
                        .build()
                );
            }
            return this.fetchWithDedup(line, headers, body, key);
        });
    }

    /**
     * Fetches from origin with signal-based request deduplication.
     * Uses shared {@link RequestDeduplicator}: first request fetches from origin
     * (which saves to NpmProxy's storage cache). Concurrent requests wait for a
     * signal, then re-fetch from origin which serves from storage cache.
     */
    private CompletableFuture<Response> fetchWithDedup(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        return this.deduplicator.deduplicate(
            key,
            () -> this.doFetch(line, headers, body, key)
        ).thenCompose(signal -> this.handleSignal(signal, line, headers, key));
    }

    /**
     * Perform the actual fetch from origin, returning a FetchSignal.
     */
    private CompletableFuture<FetchSignal> doFetch(
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
                    this.negativeCache.cacheNotFound(key);
                    this.recordProxyMetric("not_found", duration);
                    return FetchSignal.NOT_FOUND;
                }
                if (response.status().success()
                    || response.status().code() == 304) {
                    this.recordProxyMetric("success", duration);
                    return FetchSignal.SUCCESS;
                }
                if (response.status().code() >= 500) {
                    this.recordProxyMetric("error", duration);
                    this.recordUpstreamErrorMetric(
                        new RuntimeException("HTTP " + response.status().code())
                    );
                } else {
                    this.recordProxyMetric("client_error", duration);
                }
                return FetchSignal.ERROR;
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("NPM proxy: upstream request failed")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .error(error)
                    .log();
                return FetchSignal.ERROR;
            });
    }

    /**
     * Handle result for a request based on the dedup signal.
     */
    private CompletableFuture<Response> handleSignal(
        final FetchSignal signal,
        final RequestLine line,
        final Headers headers,
        final Key key
    ) {
        switch (signal) {
            case SUCCESS:
                // Data is now in NpmProxy's storage cache — re-fetch from origin
                // which will serve from cache (no upstream request)
                return this.origin.response(line, headers, Content.EMPTY);
            case NOT_FOUND:
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            case ERROR:
            default:
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unavailable()
                        .textBody("Upstream temporarily unavailable - please retry")
                        .build()
                );
        }
    }

    /**
     * Records proxy request metric.
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
     * Records upstream error metric.
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
     * Records metric safely, ignoring errors.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.npm")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
