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
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * PyPI proxy slice with negative, metadata and integrity-verified caching.
 * Wraps PyProxySlice to add a caching layer that prevents repeated 404
 * requests and caches package metadata.
 *
 * <p>Primary artifact writes (wheels / sdists / zip archives) flow through
 * {@link ProxyCacheWriter#streamThroughAndCommit} (T-P06): bytes are tee'd
 * to both the client and the local temp file in a single pass, so the
 * client receives TTFB at upstream TTFB + a few ms rather than after the
 * full body buffers + sidecar verification. The PyPI-declared sidecars
 * (MD5 / SHA-256 / SHA-512) are still verified against the downloaded
 * bytes; on mismatch the cache stays empty (the next request re-fetches
 * cleanly) while the in-flight bytes have already been served — a
 * conscious trade-off matching the Maven primary path. Concurrent
 * requests for the same uncached primary collapse via {@link SingleFlight}
 * to a single upstream call.
 *
 * @since 1.0
 */
public final class CachedPyProxySlice implements Slice {

    /**
     * Primary artifact extensions that participate in the coupled
     * primary+sidecar write path via {@link ProxyCacheWriter}.
     */
    private static final List<String> PRIMARY_EXTENSIONS = List.of(
        ".whl", ".tar.gz", ".zip"
    );

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
     * Optional raw storage used by {@link ProxyCacheWriter} to land the
     * primary + sidecars atomically. Empty when the slice runs without a
     * file-backed cache; in that case the legacy flow is used unchanged.
     */
    private final Optional<Storage> rawStorage;

    /**
     * Single-source-of-truth cache writer introduced by WI-07 (§9.5 of the
     * v2.2 target architecture). Fetches the primary + every PyPI sidecar
     * (MD5 / SHA-256 / SHA-512) in one coupled batch, verifies each
     * declared claim against the bytes we just downloaded, and atomically
     * commits the pair. Null when {@link #rawStorage} is empty.
     */
    private final ProxyCacheWriter cacheWriter;

    /**
     * Per-key single-flight gate for the primary-artifact path (T-P06).
     * Concurrent callers for the same uncached wheel/sdist collapse to a
     * single upstream call; followers wait on the gate then re-enter
     * {@link #verifyAndServePrimary} which hits the now-warm cache.
     */
    private final SingleFlight<Key, Void> primarySingleFlight;

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
    public CachedPyProxySlice(
        final Slice origin,
        final Optional<Storage> storage,
        final Duration negativeCacheTtl, // NOPMD UnusedFormalParameter - deprecated overload; ignored, settings come from unified NegativeCacheConfig
        final boolean negativeCacheEnabled, // NOPMD UnusedFormalParameter - deprecated overload; ignored, settings come from unified NegativeCacheConfig
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
        this.negativeCache = NegativeCacheRegistry.instance().sharedCache();
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
        this.rawStorage = storage;
        this.cacheWriter = storage
            .map(raw -> new ProxyCacheWriter(raw, repoName, meterRegistry()))
            .orElse(null);
        this.primarySingleFlight = new SingleFlight<>(
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
        final Key key = new KeyFromPath(path);

        // Check negative cache first (404s)
        if (this.negativeCache.isKnown404(
            com.auto1.pantera.http.cache.NegativeCacheKey.fromPath(
                this.repoName, this.repoType, path))) {
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

        // WI-07 §9.5 — integrity-verified atomic primary+sidecar write on
        // cache-miss. Runs only when we have a file-backed storage and the
        // requested path is a primary artifact. All other paths fall
        // through to the existing metadata / origin flow unchanged.
        if (this.cacheWriter != null && isPrimaryArtifact(path)) {
            return this.verifyAndServePrimary(line, key, path);
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
     * Check if path represents a PyPI primary artifact (wheel / sdist /
     * zip archive) that should be routed through {@link ProxyCacheWriter}.
     *
     * @param path Request path.
     * @return {@code true} if the path ends with a primary-artifact extension.
     */
    private static boolean isPrimaryArtifact(final String path) {
        if (path.endsWith("/")) {
            return false;
        }
        final String lower = path.toLowerCase(Locale.ROOT);
        for (final String ext : PRIMARY_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
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
                    this.negativeCache.cacheNotFound(
                        com.auto1.pantera.http.cache.NegativeCacheKey.fromPath(
                            this.repoName, this.repoType, key.string()));
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

    // ===== T-P06: stream-through primary path =====

    /**
     * Primary-artifact flow: if the cache already has the primary, serve
     * from the cache; otherwise fetch the primary + every declared sidecar
     * upstream and tee bytes to both the client and storage in one pass
     * via {@link ProxyCacheWriter#streamThroughAndCommit}. Concurrent
     * callers for the same uncached primary collapse via
     * {@link #primarySingleFlight} — only the leader fires upstream;
     * followers wait then re-enter this method against the now-warm
     * cache.
     *
     * <p>Trade-off vs. the legacy buffered path: integrity failures no
     * longer fail-closed for the in-flight client (bytes are already in
     * the response stream when the sidecar comparison runs) — the cache
     * stays empty so the next request re-fetches cleanly. Matches the
     * Maven primary-path decision.
     */
    private CompletableFuture<Response> verifyAndServePrimary(
        final RequestLine line, final Key key, final String path
    ) {
        final Storage storage = this.rawStorage.orElseThrow();
        return storage.exists(key).thenCompose(present -> {
            if (present) {
                return this.serveFromCache(storage, key);
            }
            // T-P06: single-flight the cache-miss leg. The leader streams
            // upstream → tee → client + storage; followers park on the
            // gate and re-enter verifyAndServePrimary which now hits the
            // warm cache.
            final boolean[] isLeader = {false};
            final CompletableFuture<Void> leaderGate = new CompletableFuture<>();
            final CompletableFuture<Void> gate = this.primarySingleFlight.load(
                key,
                () -> {
                    isLeader[0] = true;
                    return leaderGate;
                }
            );
            if (isLeader[0]) {
                return this.streamPrimary(line, key, path, leaderGate);
            }
            return gate.exceptionally(err -> null)
                .thenCompose(ignored -> this.verifyAndServePrimary(line, key, path));
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("PyPI primary-artifact verify-and-serve failed; returning 502")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("url.path", path)
                .error(err)
                .log();
            return ResponseBuilder.badGateway().build();
        });
    }

    /**
     * Stream-through primary fetch: tee upstream body bytes to the client
     * and the cache temp file in a single pass via
     * {@link ProxyCacheWriter#streamThroughAndCommit}. The
     * {@code leaderGate} resolves when the cache write is durable so
     * followers parked on {@link #primarySingleFlight} can re-enter
     * {@link #verifyAndServePrimary} against the warm cache. Integrity
     * failures keep the cache empty for the next request (the in-flight
     * bytes were already streamed to the client).
     */
    private CompletableFuture<Response> streamPrimary(
        final RequestLine line,
        final Key key,
        final String path,
        final CompletableFuture<Void> leaderGate
    ) {
        final String upstream = this.upstreamUrl + path;
        final RequestContext ctx = new RequestContext(
            org.apache.logging.log4j.ThreadContext.get("trace.id"),
            null,
            this.repoName,
            path
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> this.fetchSidecar(line, ".sha256"));
        sidecars.put(ChecksumAlgo.MD5, () -> this.fetchSidecar(line, ".md5"));
        sidecars.put(ChecksumAlgo.SHA512, () -> this.fetchSidecar(line, ".sha512"));
        return this.origin.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    // Drain non-2xx body to release the connection.
                    resp.body().asBytesFuture();
                    if (!leaderGate.isDone()) {
                        leaderGate.complete(null);
                    }
                    final int status = resp.status().code();
                    if (status >= 400 && status < 500) {
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.badGateway()
                            .textBody("Upstream temporarily unavailable")
                            .build()
                    );
                }
                return this.cacheWriter.streamThroughAndCommit(
                    key,
                    upstream,
                    resp.body().size(),
                    resp.body(),
                    sidecars,
                    // PyPI sidecars (.sha256/.md5/.sha512) live in
                    // NON_BLOCKING_DEFAULT; pass an empty non-blocking set so
                    // a mismatch keeps the cache empty (deferred path would
                    // commit primary then log on mismatch).
                    Collections.emptySet(),
                    ctx
                ).toCompletableFuture().thenApply(result -> {
                    if (result instanceof Result.Err<ProxyCacheWriter.StreamedArtifact> err) {
                        if (!leaderGate.isDone()) {
                            leaderGate.complete(null);
                        }
                        if (err.fault() instanceof Fault.UpstreamIntegrity ui) {
                            return ResponseBuilder.badGateway()
                                .header(
                                    "X-Pantera-Fault",
                                    "upstream-integrity:"
                                        + ui.algo().name().toLowerCase(Locale.ROOT)
                                )
                                .textBody("Upstream integrity verification failed")
                                .build();
                        }
                        return ResponseBuilder.badGateway()
                            .textBody("Upstream temporarily unavailable")
                            .build();
                    }
                    @SuppressWarnings("unchecked")
                    final ProxyCacheWriter.StreamedArtifact artifact =
                        ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
                    // Release followers only after the cache write commits.
                    artifact.verificationOutcome()
                        .whenComplete((r2, e2) -> {
                            if (!leaderGate.isDone()) {
                                leaderGate.complete(null);
                            }
                        });
                    return ResponseBuilder.ok().body(artifact.body()).build();
                });
            })
            .exceptionally(err -> {
                if (!leaderGate.isDone()) {
                    leaderGate.complete(null);
                }
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("PyPI stream-through primary fetch failed; returning 502")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", path)
                    .error(err)
                    .log();
                return ResponseBuilder.badGateway()
                    .textBody("Upstream temporarily unavailable")
                    .build();
            });
    }

    /**
     * Fetch a sidecar for the primary at {@code line}. Returns
     * {@link Optional#empty()} for 4xx/5xx so the writer treats the
     * sidecar as absent; I/O errors collapse to empty so a transient
     * sidecar failure never blocks the primary write.
     */
    private CompletionStage<Optional<InputStream>> fetchSidecar(
        final RequestLine primary, final String extension
    ) {
        final String sidecarPath = primary.uri().getPath() + extension;
        final RequestLine sidecarLine = new RequestLine(RqMethod.GET, sidecarPath);
        return this.origin.response(sidecarLine, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return resp.body().asBytesFuture()
                        .thenApply(ignored -> Optional.<InputStream>empty());
                }
                return resp.body().asBytesFuture()
                    .thenApply(bytes -> Optional.<InputStream>of(
                        new ByteArrayInputStream(bytes)
                    ));
            })
            .exceptionally(ignored -> Optional.<InputStream>empty());
    }

    /**
     * Serve the primary from storage after a successful atomic write.
     */
    private CompletableFuture<Response> serveFromCache(
        final Storage storage, final Key key
    ) {
        return storage.value(key).thenApply(content ->
            ResponseBuilder.ok().body(content).build()
        );
    }

    /**
     * Resolve the shared micrometer registry when metrics are enabled.
     *
     * @return Registry or {@code null} when metrics have not been
     *         initialised (e.g. test suites that skip bootstrap).
     */
    private static MeterRegistry meterRegistry() {
        try {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                return com.auto1.pantera.metrics.MicrometerMetrics.getInstance().getRegistry();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.pypi")
                .message("MicrometerMetrics registry unavailable; writer will run without metrics")
                .error(ex)
                .log();
        }
        return null;
    }

}
