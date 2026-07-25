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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.Counter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proxies the Go checksum-database (sumdb) endpoints
 * ({@code /sumdb/&lt;name&gt;/supported|lookup/&lt;mod&gt;@&lt;v&gt;|tile/...})
 * to the same upstream(s) a {@code go-proxy} repository already fetches
 * modules from, per the GOPROXY-protocol convention of forwarding
 * {@code $GOPROXY/sumdb/<name>/<path>} verbatim (S6, WS4-go.4).
 *
 * <p>Before this handler existed, {@code /sumdb/} requests fell through
 * {@code CachedProxySlice}'s generic {@code fetchThroughCache} path and
 * were cached forever under the raw request path as if they were an
 * ordinary artifact — accidental, unbounded, and wrong. Clients were
 * left with no way to keep Go checksum-database verification
 * ({@code GOSUMDB}) on while also surviving an upstream outage, so the
 * docs recommended disabling it (a removed Go 1.18 no-op,
 * {@code GONOSUMCHECK}/{@code GONOSUMDB}) or the blunt {@code
 * GOSUMDB=off} escape hatch.</p>
 *
 * <p>{@code lookup} and {@code tile} responses are content-addressed
 * (keyed by module@version or a fixed tile coordinate) and therefore
 * immutable: once cached under {@link CacheControl.Standard#ALWAYS} a
 * hit is served forever with zero upstream calls, which is exactly what
 * makes a previously-seen module's checksum verification offline-safe.
 * {@code supported} is a live, uncached probe — it is a fast existence
 * check with no content to cache and no correctness reason to freeze.</p>
 *
 * @since 2.3.0
 */
final class GoSumdbHandler {

    /**
     * Path prefix that identifies a sumdb request.
     */
    private static final String PREFIX = "/sumdb/";

    /**
     * Suffix identifying the (uncached, live-probed) support-check
     * endpoint {@code /sumdb/<name>/supported}.
     */
    private static final String SUPPORTED_SUFFIX = "/supported";

    /**
     * Path segment identifying an (immutably cached) checksum lookup
     * request {@code /sumdb/<name>/lookup/<module>@<version>}.
     */
    private static final String LOOKUP_SEGMENT = "/lookup/";

    /**
     * Path segment identifying an (immutably cached) Merkle-tile request
     * {@code /sumdb/<name>/tile/...}.
     */
    private static final String TILE_SEGMENT = "/tile/";

    /**
     * Body used when the upstream call itself failed (connection
     * refused, timeout, ...) with no cached copy to fall back to. A
     * genuinely uncached sumdb entry has no stale copy to serve — unlike
     * the resolution-surface loader (WS4-go.2), immutable content that
     * was never fetched successfully has nothing to degrade to.
     */
    private static final byte[] UPSTREAM_UNAVAILABLE_BODY =
        "Upstream temporarily unavailable".getBytes(StandardCharsets.UTF_8);

    /**
     * Micrometer counter name for sumdb cache hit/miss (bounded {@code
     * repo_name} tag, capped by {@code RepoNameMeterFilter}; bounded
     * {@code kind} tag — only ever {@code lookup} or {@code tile}).
     */
    private static final String CACHE_COUNTER = "pantera.go.sumdb.cache";

    /**
     * Upstream Go module proxy slice shared with {@code CachedProxySlice}.
     */
    private final Slice upstream;

    /**
     * Storage-backed cache the immutable lookup/tile bodies are read
     * through.
     */
    private final Cache cache;

    /**
     * Repository name — logging/metrics only.
     */
    private final String repoName;

    /**
     * New handler.
     *
     * @param upstream Upstream Go module proxy slice
     * @param cache Storage-backed cache for lookup/tile bodies
     * @param repoName Repository name (logging/metrics only)
     */
    GoSumdbHandler(final Slice upstream, final Cache cache, final String repoName) {
        this.upstream = upstream;
        this.cache = cache;
        this.repoName = repoName;
    }

    /**
     * Whether the handler should intercept the given path.
     *
     * @param path Request path
     * @return true for any {@code /sumdb/...} path
     */
    boolean matches(final String path) {
        return path != null && path.startsWith(PREFIX) && path.length() > PREFIX.length();
    }

    /**
     * Handle a {@code /sumdb/} request: {@code supported} is probed live
     * (uncached), {@code lookup}/{@code tile} are read through the
     * immutable cache.
     *
     * @param line Request line (must be a {@code /sumdb/} path)
     * @return Future response
     */
    CompletableFuture<Response> handle(final RequestLine line) {
        final String path = line.uri().getPath();
        if (path.endsWith(SUPPORTED_SUFFIX)) {
            return this.probeSupported(line);
        }
        if (path.contains(LOOKUP_SEGMENT)) {
            return this.loadImmutable(line, path, "lookup");
        }
        if (path.contains(TILE_SEGMENT)) {
            return this.loadImmutable(line, path, "tile");
        }
        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
    }

    /**
     * Live, uncached probe of {@code /sumdb/<name>/supported}: 200 means
     * this proxy can serve the named checksum database, anything else
     * means it cannot. Consumes the upstream body on every branch
     * (never leak a Vert.x/Jetty body).
     */
    private CompletableFuture<Response> probeSupported(final RequestLine line) {
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture().thenApply(ignored -> {
                if (resp.status().success()) {
                    return ResponseBuilder.ok().build();
                }
                GoSumdbHandler.logSupportedProbeFailure(
                    this.repoName, line.uri().getPath(), "status " + resp.status().code(), null
                );
                return ResponseBuilder.notFound().build();
            }))
            .exceptionally(err -> {
                GoSumdbHandler.logSupportedProbeFailure(
                    this.repoName, line.uri().getPath(), "upstream call failed", err
                );
                return ResponseBuilder.notFound().build();
            });
    }

    /**
     * Read a {@code lookup}/{@code tile} body through the immutable
     * cache: a cheap presence check first (mirrors the two-phase shape
     * used throughout this adapter — a side-effecting {@link Remote}
     * must never be evaluated on a cache hit), then a genuinely
     * single-fetch upstream call only on a confirmed miss.
     */
    private CompletableFuture<Response> loadImmutable(
        final RequestLine line, final String path, final String kind
    ) {
        final Key key = new KeyFromPath(path);
        return this.cache.load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    GoSumdbHandler.recordCacheResult(this.repoName, kind, "hit");
                    return cached.get().asBytesFuture()
                        .thenApply(bytes -> ResponseBuilder.ok().body(bytes).build());
                }
                GoSumdbHandler.recordCacheResult(this.repoName, kind, "miss");
                return this.fetchAndCache(line, key, path);
            })
            .toCompletableFuture();
    }

    /**
     * Confirmed cache miss — fetch once from upstream and cache
     * immutably on success; forward the upstream status/body unchanged
     * on failure (never fabricate a 200, never cache a failure).
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line, final Key key, final String path
    ) {
        final AtomicReference<RsStatus> forwardStatus =
            new AtomicReference<>(RsStatus.BAD_GATEWAY);
        final AtomicReference<byte[]> forwardBody =
            new AtomicReference<>(UPSTREAM_UNAVAILABLE_BODY);
        final Remote remote = () -> this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture()
                .<Optional<? extends Content>>thenApply(bytes -> {
                    if (resp.status().success()) {
                        return Optional.of(new Content.From(bytes));
                    }
                    forwardStatus.set(resp.status());
                    forwardBody.set(bytes);
                    return Optional.empty();
                }))
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Go sumdb fetch failed")
                    .eventCategory("web")
                    .eventAction("sumdb_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", path)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            });
        return this.cache.load(key, remote, CacheControl.Standard.ALWAYS)
            .thenCompose(opt -> {
                if (opt.isPresent()) {
                    return opt.get().asBytesFuture()
                        .thenApply(bytes -> ResponseBuilder.ok().body(bytes).build());
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.from(forwardStatus.get()).body(forwardBody.get()).build()
                );
            })
            .toCompletableFuture();
    }

    /**
     * Log a {@code supported}-probe failure as a state transition (never
     * just a counter — CLAUDE.md observability doctrine).
     */
    private static void logSupportedProbeFailure(
        final String repoName, final String path, final String reason, final Throwable err
    ) {
        final EcsLogger log = EcsLogger.warn("com.auto1.pantera.http")
            .message("Go sumdb 'supported' probe failed: " + reason)
            .eventCategory("web")
            .eventAction("sumdb_supported_probe")
            .eventOutcome("failure")
            .field("repository.name", repoName)
            .field("url.path", path)
            .field("log.source", "application");
        if (err != null) {
            log.error(err);
        }
        log.log();
    }

    /**
     * Record a sumdb cache hit/miss. Guarded by {@link
     * MicrometerMetrics#isInitialized()} and never allowed to escape the
     * serve path — a metrics-registration race must not fail a request.
     *
     * @param repoName Repository name (bounded {@code repo_name} tag)
     * @param kind {@code lookup} or {@code tile} (bounded tag)
     * @param result {@code hit} or {@code miss} (bounded tag)
     */
    private static void recordCacheResult(
        final String repoName, final String kind, final String result
    ) {
        if (!MicrometerMetrics.isInitialized()) {
            return;
        }
        try {
            Counter.builder(CACHE_COUNTER)
                .description("Go sumdb lookup/tile immutable-cache hit/miss")
                .tag("repo_name", repoName == null ? "unknown" : repoName)
                .tag("kind", kind)
                .tag("result", result)
                .register(MicrometerMetrics.getInstance().getRegistry())
                .increment();
        } catch (final RuntimeException ignored) {
            // EXPECTED: metrics registration must never escape the serve
            // path (registry races during shutdown, etc).
        }
    }
}
