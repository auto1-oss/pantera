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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.FetchSignal;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * NPM proxy slice with negative caching and signal-based request deduplication.
 * Wraps NpmProxySlice to add caching layer that prevents repeated
 * 404 requests and deduplicates concurrent requests.
 *
 * <p>Uses the unified {@link SingleFlight} coalescer (WI-05): concurrent
 * requests for the same package wait for the first request to complete, then
 * fetch from NpmProxy's storage cache. This eliminates memory buffering while
 * maintaining full deduplication. The retained {@link FetchSignal} enum is
 * the same signal contract as the legacy path — only the coalescer
 * implementation changed.</p>
 *
 * <p>G7 (T-P05, analysis/plan/v2/IMPLEMENTATION.md): npm tarball cache
 * writes already stream-through via {@code RxNpmProxyStorage.saveStreamThrough}
 * (Phase 12) — the upstream body is tee'd to both the client publisher and
 * an in-memory buffer that lands in storage on completion. Migration to
 * {@code ProxyCacheWriter.streamThroughAndCommit} would replace the
 * NpmProxy / NpmProxyStorage abstraction (RxJava {@code Maybe} semantics)
 * with no TTFB win because the existing path already emits bytes to the
 * client as the upstream delivers them. The Phase 12 buffer is in-memory
 * (typical tarball 10 KB–1 MB; 50 MB worst case) versus the
 * ProxyCacheWriter NIO temp-file pattern — a future heap-pressure
 * optimisation, not a correctness fix. NPM tarballs are therefore
 * <em>already correct</em> for the universal-tee gap (G7); the
 * adapter-level structural change is deferred.</p>
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
     * Per-key request coalescer. Concurrent requests for the same cache key
     * share one upstream fetch, each receiving the same {@link FetchSignal}
     * terminal state. Wired in WI-05.
     */
    private final SingleFlight<Key, FetchSignal> deduplicator;

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
        final Optional<Storage> storage, //NOPMD UnusedFormalParameter - kept for source compatibility; the metadata-cache short-circuit was removed (body-less response bug) but callers still pass storage handle.
        final String repoName,
        final String upstreamUrl,
        final String repoType
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
        this.repoType = repoType;
        this.negativeCache = NegativeCacheRegistry.instance().sharedCache();
        // 5-minute zombie TTL (PANTERA_DEDUP_MAX_AGE_MS = 300 000 ms).
        // 10K max entries bounds memory.
        this.deduplicator = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
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
        if (this.negativeCache.isKnown404(this.negKey(path))) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
        // Tarball / package.json reads always traverse dedup → origin; the
        // origin slice serves from NpmProxy's storage cache on a hit. The
        // metadata store only tracks response headers (not bodies), so a
        // hit there cannot produce a complete 200 — short-circuiting on it
        // would return Content-Length pointing at bytes we don't have.
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
     * Fetches from origin with signal-based request coalescing.
     * Uses shared {@link SingleFlight}: the leader (the request whose loader
     * actually runs) fetches from origin once and serves THAT response
     * directly. Concurrent followers wait for the signal, then re-fetch from
     * origin, which serves from NpmProxy's now-warm storage cache.
     *
     * <p>The leader must NOT re-fetch: origin traversal is where per-request
     * side effects live (the {@code artifact_resolution}/{@code
     * artifact_access} audit records, phase metrics), so a probe-then-refetch
     * leader emitted every audit record twice for a single client request —
     * same trace.id, milliseconds apart. It also discarded the probe
     * response's body unconsumed. One client request = one origin traversal.</p>
     */
    private CompletableFuture<Response> fetchWithDedup(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key
    ) {
        // Set only by THIS caller's loader. The loader runs for exactly one
        // caller per concurrent burst (the leader); followers join the
        // in-flight signal and their reference stays null.
        final java.util.concurrent.atomic.AtomicReference<Response> leaderResponse =
            new java.util.concurrent.atomic.AtomicReference<>();
        return this.deduplicator.load(
            key,
            () -> this.doFetch(line, headers, body, key, leaderResponse)
        ).thenCompose(signal -> {
            final Response captured = leaderResponse.get();
            if (captured != null) {
                if (signal == FetchSignal.SUCCESS) {
                    // Leader: serve the response we already have — origin was
                    // traversed exactly once for this request.
                    return CompletableFuture.completedFuture(captured);
                }
                // Leader on a non-success signal: handleSignal builds the
                // synthetic 404/503 (the raw upstream status must not leak —
                // RaceSlice's fallback contract depends on the 404 mapping).
                // Drain the captured body so the publisher is not leaked.
                captured.body().asBytesFuture().whenComplete((b, e) -> { });
            }
            return this.handleSignal(signal, line, headers);
        });
    }

    /**
     * Perform the actual fetch from origin, returning a FetchSignal and
     * capturing the raw response for the leader's direct serve.
     */
    private CompletableFuture<FetchSignal> doFetch(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final Key key,
        final java.util.concurrent.atomic.AtomicReference<Response> capture
    ) {
        final long startTime = System.currentTimeMillis();
        return this.origin.response(line, headers, body)
            .thenApply(response -> {
                capture.set(response);
                final long duration = System.currentTimeMillis() - startTime;
                if (response.status().code() == 404) {
                    this.negativeCache.cacheNotFound(this.negKey(key.string()));
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
                    return FetchSignal.ERROR;
                }
                // Non-404 4xx (403 rate-limit / unauthorized, 410 Gone for
                // unpublished, 451, 409, etc.) means "this remote doesn't
                // serve this artifact" — semantically equivalent to NOT_FOUND
                // from the RaceSlice's perspective. Mapping to ERROR (→ 503)
                // would short-circuit the race because RaceSlice's contract
                // is "404 → try next remote; anything else → this remote
                // wins". Surface as NOT_FOUND so a multi-remote npm proxy
                // (e.g. npmjs + a private mirror) can fall back when one
                // remote rate-limits. The "client_error" metric still fires
                // for observability.
                this.recordProxyMetric("client_error", duration);
                return FetchSignal.NOT_FOUND;
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("NPM proxy: upstream request failed")
                    .eventCategory("web")
                    .eventAction("proxy_request")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", key.string())
                    .error(error)
                    .field("log.source", "application")
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
        final Headers headers
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
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.npm")
                .message("Failed to record metric")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Build a structured negative-cache key for a request path.
     */
    private com.auto1.pantera.http.cache.NegativeCacheKey negKey(final String path) {
        return com.auto1.pantera.http.cache.NegativeCacheKey.fromPath(
            this.repoName, this.repoType, path);
    }
}
