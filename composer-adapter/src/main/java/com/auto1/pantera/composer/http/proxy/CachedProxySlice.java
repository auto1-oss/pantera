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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.composer.JsonPackages;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Composer proxy slice — pure cache + URL-rewrite for package metadata.
 * Per-version cooldown filtering is owned by
 * {@link com.auto1.pantera.composer.cooldown.ComposerPackageMetadataHandler}
 * and {@link com.auto1.pantera.composer.cooldown.ComposerRootPackagesHandler},
 * which sit upstream of this slice; this slice never blocks a metadata
 * response on cooldown, it only serves cached (and rewritten) bytes.
 *
 * <p>Primary dist archive downloads (the {@code *.zip} / {@code *.tar} /
 * {@code *.phar} files) do NOT flow through this slice — they are routed
 * to {@link ProxyDownloadSlice} by {@link ComposerProxySlice}'s dispatch,
 * which resolves the real (frequently cross-host — GitHub, other CDNs,
 * not this repository's configured upstream) dist URL from the cached
 * packument and verifies it against the packument's declared
 * {@code dist.shasum} (WS4-composer.3/.4, S7 of
 * {@code 00-security-integrity-decisions.md}). An earlier revision of
 * this class attempted that integrity check here via a phantom
 * {@code .sha256} sidecar fetch (Composer has no such sidecar resource —
 * the real claim is the SHA-1 {@code dist.shasum} field inline in the
 * packument) gated on a path shape ({@code PACKAGE}, i.e.
 * {@code /p2/<vendor>/<pkg>.json}) that a rewritten dist URL
 * ({@code /dist/<vendor>/<pkg>/<version>.zip}) never matches — so the
 * code never ran in production. It has been removed in favour of the
 * real, reachable integrity path in {@link ProxyDownloadSlice}.
 */
final class CachedProxySlice implements Slice {

    private final Slice remote;
    private final Cache cache;
    private final Repository repo;

    /**
     * Proxy artifact events queue.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Base URL for metadata rewriting.
     */
    private final String baseUrl;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Packages currently being refreshed in background (stale-while-revalidate).
     */
    private final ConcurrentHashMap.KeySetView<String, Boolean> refreshing;

    /**
     * Store for upstream Last-Modified headers (conditional requests).
     */
    private final ConcurrentHashMap<String, String> lastModifiedStore;

    /**
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     */
    CachedProxySlice(Slice remote, Repository repo, Cache cache) {
        this(remote, repo, cache, Optional.empty(), "composer",
            "http://localhost:8080", "unknown"
        );
    }

    /**
     * Full constructor.
     *
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param baseUrl Base URL for this Pantera instance
     */
    CachedProxySlice(
        final Slice remote,
        final Repository repo,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String baseUrl
    ) {
        this(remote, repo, cache, events, rname, baseUrl, "unknown");
    }

    /**
     * Full constructor with upstream URL for metrics.
     *
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param baseUrl Base URL for this Pantera instance
     * @param upstreamUrl Upstream URL for metrics
     */
    CachedProxySlice(
        final Slice remote,
        final Repository repo,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String baseUrl,
        final String upstreamUrl
    ) {
        this.remote = remote;
        this.cache = cache;
        this.repo = repo;
        this.events = events;
        this.rname = rname;
        this.baseUrl = baseUrl;
        this.upstreamUrl = upstreamUrl;
        this.refreshing = ConcurrentHashMap.newKeySet();
        this.lastModifiedStore = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String path = line.uri().getPath();
            EcsLogger.info("com.auto1.pantera.composer")
                .message("Composer proxy request")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .field("log.source", "application")
                .log();

            // Keep ~dev suffix in cache key to avoid collision between stable and dev metadata
            final String name = path
                .replaceAll("^/p2?/", "")
                .replaceAll("\\^.*", "")
                .replaceAll(".json$", "");

            // Check cache FIRST before any network calls — offline mode
            // serves cached content even when upstream is unreachable.
            return this.checkCacheFirst(line, headers, name);
        });
    }

    /**
     * Check cache first to keep offline mode functional — cached
     * metadata is served even when the upstream is unavailable.
     *
     * @param line Request line
     * @param headers Inbound request headers (read for client
     *  {@code If-Modified-Since} on the served response — WS6.2)
     * @param name Package name
     * @return Response future
     */
    private CompletableFuture<Response> checkCacheFirst(
        final RequestLine line,
        final Headers headers,
        final String name
    ) {
        // Check storage cache FIRST before any network calls
        return new FromStorageCache(this.repo.storage()).load(
            new Key.From(name),
            Remote.EMPTY,
            CacheControl.Standard.ALWAYS
        ).thenCompose(cached -> {
            if (cached.isPresent()) {
                EcsLogger.info("com.auto1.pantera.composer")
                    .message("Cache hit, serving cached metadata (offline-safe)")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", name)
                    .field("log.source", "application")
                    .log();
                return cached.get().asBytesFuture().thenCompose(bytes -> {
                    // Stale-while-revalidate: check freshness, trigger background refresh if stale
                    return new CacheTimeControl(this.repo.storage()).validate(
                        new Key.From(name), Remote.EMPTY
                    ).thenCompose(fresh -> {
                        if (!fresh) {
                            this.backgroundRefresh(line, name);
                        }
                        return this.serveCachedMetadata(line, headers, bytes);
                    });
                });
            }
            // Cache MISS - fetch through cache. Per-version cooldown is
            // owned by ComposerPackageMetadataHandler / ComposerRootPackagesHandler;
            // CachedProxySlice's job is pure cache + URL-rewrite + integrity.
            return this.fetchThroughCache(line, headers, name);
        }).toCompletableFuture();
    }

    /**
     * Serve cached metadata bytes: rewrite URLs (idempotent — already
     * rewritten at write time), build response.
     *
     * @param line Request line (path is the {@link #lastModifiedStore} key)
     * @param headers Inbound request headers (client conditional GET)
     * @param bytes Cached metadata bytes
     * @return Response future
     */
    private CompletableFuture<Response> serveCachedMetadata(
        final RequestLine line, final Headers headers, final byte[] bytes
    ) {
        final byte[] rewritten = this.rewriteMetadata(bytes);
        return CompletableFuture.completedFuture(
            this.buildMetadataResponse(line, headers, rewritten)
        );
    }

    /**
     * Build the served metadata response — the served-side half of the
     * conditional-request contract (WS6.2; {@link #revalidateOrRefresh} is
     * the upstream-facing half). Emits the upstream {@code Last-Modified}
     * captured for this path in {@link #lastModifiedStore} (populated in
     * {@link #packageFromRemote}) and, when the client sent its own
     * {@code If-Modified-Since} matching or newer than that value, returns
     * a bodiless {@code 304} instead of re-transferring the packument.
     * Falls back to a plain {@code 200} with no {@code Last-Modified}
     * header when nothing has been captured for this path yet (a cold
     * cache entry written before this fix, or one populated by a path this
     * JVM instance never itself fetched — {@link #lastModifiedStore} is an
     * in-memory, per-instance map, matching the existing scope of the
     * upstream-side conditional store).
     *
     * @param line Request line (path is the {@link #lastModifiedStore} key)
     * @param headers Inbound request headers
     * @param bytes Response body (already rewritten)
     * @return 200 OK with body, or 304 Not Modified with no body
     */
    private Response buildMetadataResponse(
        final RequestLine line, final Headers headers, final byte[] bytes
    ) {
        final String stored = this.lastModifiedStore.get(line.uri().getPath());
        if (stored != null) {
            final java.util.List<String> clientSince =
                new com.auto1.pantera.http.rq.RqHeaders(headers, "If-Modified-Since");
            if (!clientSince.isEmpty() && notModifiedSince(stored, clientSince.get(0))) {
                EcsLogger.info("com.auto1.pantera.composer")
                    .message("Client conditional GET matched cached Last-Modified — 304")
                    .eventCategory("web")
                    .eventAction("conditional_get")
                    .eventOutcome("success")
                    .field("url.path", line.uri().getPath())
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                    .header("Last-Modified", stored)
                    .build();
            }
        }
        final ResponseBuilder builder = ResponseBuilder.ok()
            .header("Content-Type", "application/json")
            .body(new Content.From(bytes));
        if (stored != null) {
            builder.header("Last-Modified", stored);
        }
        return builder.build();
    }

    /**
     * Compare two RFC 1123 HTTP-dates: true when {@code stored} (the
     * resource's captured Last-Modified instant) is at or before
     * {@code clientSince} (the client's {@code If-Modified-Since} instant)
     * — i.e. the resource has not changed since the client last saw it.
     * Unparseable dates fail open to "modified" (a full 200) rather than
     * risk a false 304 masking real content.
     *
     * @param stored Captured upstream Last-Modified (RFC 1123)
     * @param clientSince Client's If-Modified-Since header value (RFC 1123)
     * @return true if the resource is not modified since clientSince
     */
    private static boolean notModifiedSince(final String stored, final String clientSince) {
        try {
            final Instant storedInstant =
                Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(stored));
            final Instant clientInstant =
                Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(clientSince));
            return !storedInstant.isAfter(clientInstant);
        } catch (final DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Trigger background refresh of metadata (stale-while-revalidate pattern).
     * Serves stale content immediately while refreshing in background.
     *
     * @param line Request line
     * @param name Package name
     */
    private void backgroundRefresh(
        final RequestLine line,
        final String name
    ) {
        if (this.refreshing.add(name)) {
            CompletableFuture.runAsync(() -> {
                try {
                    this.revalidateOrRefresh(line, name).join();
                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Background refresh completed")
                        .eventCategory("database")
                        .eventAction("stale_while_revalidate")
                        .eventOutcome("success")
                        .field("package.name", name)
                        .field("log.source", "application")
                        .log();
                } catch (final Exception err) {
                    EcsLogger.warn("com.auto1.pantera.composer")
                        .message("Background refresh failed")
                        .eventCategory("database")
                        .eventAction("stale_while_revalidate")
                        .eventOutcome("failure")
                        .field("package.name", name)
                        .error(err)
                        .field("log.source", "application")
                        .log();
                } finally {
                    this.refreshing.remove(name);
                }
            });
        }
    }

    /**
     * Conditional stale-while-revalidate (WS4-composer.7): before doing the
     * full merge + rewrite + save cycle, issue a conditional GET using the
     * upstream {@code Last-Modified} value captured on the last successful
     * fetch for this path (populated in {@link #packageFromRemote}). A
     * clean {@code 304} means nothing changed upstream — skip the
     * merge/rewrite entirely and just touch the cached entry's freshness
     * marker, so a repeat revalidation of unchanged metadata reads zero
     * upstream body bytes and does zero JSON re-parse/re-rewrite work.
     *
     * <p>Any other outcome (200 with a body, no stored {@code Last-Modified}
     * to validate against yet, or an upstream error) falls through to the
     * existing {@link #fetchThroughCache} path, which remains the single
     * source of truth for merge + URL rewriting + persistence. This trades
     * one extra upstream round trip in the (uncommon) case where content
     * genuinely changed between the conditional probe and the full fetch,
     * in exchange for not forking a second merge/rewrite implementation.
     */
    CompletableFuture<Response> revalidateOrRefresh(
        final RequestLine line, final String name
    ) {
        final String stored = this.lastModifiedStore.get(line.uri().getPath());
        if (stored == null) {
            // Background revalidation has no live client waiting — no
            // client conditional headers to honour on the (discarded)
            // returned response.
            return this.fetchThroughCache(line, Headers.EMPTY, name);
        }
        return this.remote.response(
            line, Headers.from("If-Modified-Since", stored), Content.EMPTY
        ).thenCompose(response -> {
            if (response.status().code() == RsStatus.NOT_MODIFIED.code()) {
                return response.body().asBytesFuture().thenCompose(
                    ignored -> this.touchCache(name)
                );
            }
            // Conditional probe indicates a change (or the upstream chose
            // not to honour If-Modified-Since) — drain this response and
            // fall through to the authoritative merge/rewrite/save path.
            return response.body().asBytesFuture().thenCompose(
                ignored -> this.fetchThroughCache(line, Headers.EMPTY, name)
            );
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Conditional revalidation request failed; will retry on next cycle")
                .eventCategory("web")
                .eventAction("conditional_get")
                .eventOutcome("failure")
                .field("package.name", name)
                .error(err)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badGateway().build();
        });
    }

    /**
     * A clean {@code 304}: the cached metadata is confirmed unchanged.
     * Re-saves the existing bytes unmodified so {@link CacheTimeControl}'s
     * mtime-based freshness check sees a fresh write, without re-parsing,
     * re-merging, or re-rewriting anything.
     */
    CompletableFuture<Response> touchCache(final String name) {
        final Key metadataKey = new Key.From(name + ".json");
        return this.repo.storage().value(metadataKey).thenCompose(
            content -> content.asBytesFuture().thenCompose(
                bytes -> this.repo.storage().save(metadataKey, new Content.From(bytes))
                    .thenApply(ignored -> {
                        EcsLogger.info("com.auto1.pantera.composer")
                            .message("Conditional GET 304 — metadata unchanged, freshness refreshed")
                            .eventCategory("web")
                            .eventAction("conditional_get")
                            .eventOutcome("success")
                            .field("package.name", name)
                            .field("log.source", "application")
                            .log();
                        return ResponseBuilder.ok()
                            .header("Content-Type", "application/json")
                            .body(new Content.From(bytes))
                            .build();
                    })
            )
        );
    }

    /**
     * Fetch package through cache.
     *
     * @param line Request line
     * @param headers Inbound request headers (client conditional GET on
     *  the served response — WS6.2)
     * @param name Package name
     * @return Response future
     */
    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Headers headers,
        final String name
    ) {
        // Package name for merge: strip ~dev suffix since Packagist JSON uses base name
        final String packageName = name.replaceAll("~dev$", "");
        return this.cache.load(
            new Key.From(name),  // Cache key keeps ~dev to prevent collision
            new Remote.WithErrorHandling(
                () -> this.repo.packages().thenApply(
                        pckgs -> pckgs.orElse(new JsonPackages())
                    ).thenCompose(Packages::content)
                    .thenCombine(
                        this.packageFromRemote(line),
                        (lcl, rmt) -> new MergePackage.WithRemote(packageName, lcl).merge(rmt)
                    ).thenCompose(Function.identity())
                    .thenCompose(contentOpt -> {
                        // Write-time URL rewriting: rewrite before caching
                        if (contentOpt.isPresent()) {
                            return contentOpt.get().asBytesFuture().thenApply(bytes -> {
                                final byte[] rewritten = this.rewriteMetadata(bytes);
                                EcsLogger.debug("com.auto1.pantera.composer")
                                    .message("Pre-rewrote metadata URLs at write time")
                                    .eventCategory("web")
                                    .eventAction("metadata_rewrite")
                                    .field("package.name", name)
                                    .field("log.source", "application")
                                    .log();
                                return Optional.of(
                                    (Content) new Content.From(rewritten)
                                );
                            });
                        }
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("No content from remote for package")
                            .eventCategory("web")
                            .eventAction("metadata_fetch")
                            .field("package.name", name)
                            .field("log.source", "application")
                            .log();
                        return CompletableFuture.completedFuture(
                            Optional.<Content>empty()
                        );
                    })
            ),
            new CacheTimeControl(this.repo.storage())
        ).thenCompose((java.util.Optional<? extends Content> pkgs) -> {
            if (pkgs.isEmpty()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            // Content is already pre-rewritten at write time.
            // Persist the rewritten bytes under {name}.json so
            // ProxyDownloadSlice can resolve original_url on subsequent
            // archive requests. Per-version cooldown is handled by
            // ComposerPackageMetadataHandler upstream of this slice.
            return pkgs.get().asBytesFuture().thenCompose(bytes -> {
                final Key metadataKey = new Key.From(name + ".json");
                return this.repo.storage().save(metadataKey, new Content.From(bytes))
                    .thenApply(ignored -> {
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("Saved metadata to storage")
                            .eventCategory("web")
                            .eventAction("metadata_save")
                            .field("package.name", metadataKey.string())
                            .field("log.source", "application")
                            .log();
                        return this.buildMetadataResponse(line, headers, bytes);
                    });
            });
        }).exceptionally(throwable -> {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to read cached item")
                .eventCategory("web")
                .eventAction("cache_read")
                .eventOutcome("failure")
                .error(throwable)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.notFound().build();
        }).toCompletableFuture();
    }

    /**
     * Rewrite metadata content to proxy downloads through Pantera.
     * Returns byte[] directly to avoid unnecessary Content wrapping/unwrapping.
     *
     * @param original Original metadata bytes
     * @return Rewritten metadata bytes
     */
    private byte[] rewriteMetadata(final byte[] original) {
        try {
            final String json = new String(original, StandardCharsets.UTF_8);
            final MetadataUrlRewriter rewriter = new MetadataUrlRewriter(this.baseUrl);
            return rewriter.rewrite(json);
        } catch (Exception ex) {
            EcsLogger.error("com.auto1.pantera.composer")
                .message("Failed to rewrite metadata")
                .eventCategory("web")
                .eventAction("metadata_rewrite")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
            return original;
        }
    }

    /**
     * Emit event for downloaded package.
     *
     * @param name Package name
     * @param headers Request headers
     * @param content Package content
     */
    private void emitEvent(final String name, final Headers headers, final Optional<? extends Content> content) {
        if (this.events.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Events queue is empty, cannot emit event")
                .eventCategory("web")
                .eventAction("event_creation")
                .eventOutcome("failure")
                .field("package.name", name)
                .field("log.source", "application")
                .log();
            return;
        }
        if (content.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Content is empty, cannot emit event")
                .eventCategory("web")
                .eventAction("event_creation")
                .eventOutcome("failure")
                .field("package.name", name)
                .field("log.source", "application")
                .log();
            return;
        }
        final String owner = new Login(headers).getValue();
        final Long release = this.extractReleaseDate(headers);
        this.events.get().add(
            new ProxyArtifactEvent(
                new Key.From(name),
                this.rname,
                owner,
                Optional.ofNullable(release)
            )
        );
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Added Composer proxy event (queue size: " + this.events.get().size() + ")")
            .eventCategory("web")
            .eventAction("event_creation")
            .eventOutcome("success")
            .field("package.name", name)
            .field("user.name", owner)
            .field("log.source", "application")
            .log();
    }

    /**
     * Extract release date from response headers.
     *
     * @param headers Response headers
     * @return Release timestamp in milliseconds, or null
     */
    private Long extractReleaseDate(final Headers headers) {
        try {
            return headers.stream()
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)).toEpochMilli())
                .orElse(null);
        } catch (final DateTimeParseException ex) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Failed to parse Last-Modified header for release date")
                .error(ex)
                .field("log.source", "application")
                .log();
            return null;
        }
    }

    /**
     * Obtains info about package from remote.
     * @param line The request line (usually like this `GET /p2/vendor/package.json HTTP_1_1`)
     * @return Content from respond of remote. If there were some errors,
     *  empty will be returned.
     */
    private CompletionStage<Optional<? extends Content>> packageFromRemote(
        final RequestLine line
    ) {
        final long startTime = System.currentTimeMillis();
        return new Remote.WithErrorHandling(
            () -> {
                try {
                    return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
                        .thenCompose(response -> {
                            final long duration = System.currentTimeMillis() - startTime;
                            EcsLogger.debug("com.auto1.pantera.composer")
                                .message("Remote response received")
                                .eventCategory("web")
                                .eventAction("remote_fetch")
                                .field("url.path", line.uri().getPath())
                                .field("http.response.status_code", response.status().code())
                                .field("log.source", "http")
                                .log();
                            if (response.status().success()) {
                                this.recordProxyMetric("success", duration);
                                // Store Last-Modified for conditional requests
                                response.headers().stream()
                                    .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                                    .findFirst()
                                    .ifPresent(h -> this.lastModifiedStore.put(
                                        line.uri().getPath(), h.getValue()
                                    ));
                                return CompletableFuture.completedFuture(Optional.of(response.body()));
                            }
                            // CRITICAL: Consume body to prevent Vert.x request leak
                            return response.body().asBytesFuture().thenApply(ignored -> {
                                final String result = response.status().code() == 404 ? "not_found" :
                                    (response.status().code() >= 500 ? "error" : "client_error");
                                this.recordProxyMetric(result, duration);
                                if (response.status().code() >= 500) {
                                    this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                                }
                                EcsLogger.warn("com.auto1.pantera.composer")
                                    .message("Remote returned non-success status")
                                    .eventCategory("web")
                                    .eventAction("remote_fetch")
                                    .eventOutcome("failure")
                                    .field("url.path", line.uri().getPath())
                                    .field("http.response.status_code", response.status().code())
                                    .field("log.source", "http")
                                    .log();
                                return Optional.empty();
                            });
                        });
                } catch (Exception error) {
                    final long duration = System.currentTimeMillis() - startTime;
                    this.recordProxyMetric("exception", duration);
                    this.recordUpstreamErrorMetric(error);
                    throw error;
                }
            }
        ).get();
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
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
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
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
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Failed to record metric")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

}
