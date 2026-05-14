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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.composer.JsonPackages;
import com.auto1.pantera.composer.Packages;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * Composer proxy slice with cache support, cooldown service, and event emission.
 *
 * <p>Primary artifact writes (the {@code *.zip} / {@code *.tar} / {@code *.phar}
 * dist archives) flow through {@link ProxyCacheWriter} so the packagist.org
 * {@code dist.shasum} SHA-256 sidecar is verified against the downloaded
 * bytes before anything lands in the cache — giving the Composer adapter
 * the same primary+sidecar integrity guarantee the Maven adapter received
 * in WI-07 (§9.5). The existing metadata-JSON flow (the dominant traffic
 * shape through this slice) is unchanged.
 */
final class CachedProxySlice implements Slice {

    /**
     * Primary artifact extensions that participate in the coupled
     * primary+sidecar write path via {@link ProxyCacheWriter}.
     */
    private static final List<String> PRIMARY_EXTENSIONS = List.of(
        ".zip", ".tar", ".phar"
    );

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
     * Repository type.
     */
    private final String rtype;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

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
     * Single-source-of-truth cache writer introduced by WI-07 (§9.5 of the
     * v2.2 target architecture). Fetches the primary dist archive + the
     * Composer {@code .sha256} sidecar in one coupled batch, verifies the
     * declared claim against the bytes we just downloaded, and atomically
     * commits the pair. Non-null whenever {@code repo.storage()} is set.
     */
    private final ProxyCacheWriter cacheWriter;

    /**
     * Per-key single-flight gate for the primary-artifact path (T-P07).
     * Concurrent callers for the same uncached dist archive collapse to a
     * single upstream call; followers wait on the gate then re-enter
     * {@link #verifyAndServePrimary} which hits the now-warm cache.
     */
    private final SingleFlight<Key, Void> primarySingleFlight;

    /**
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     */
    CachedProxySlice(Slice remote, Repository repo, Cache cache) {
        this(remote, repo, cache, Optional.empty(), "composer", "php",
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE,
            new RegistryBackedInspector("composer", PublishDateRegistries.instance()),
            "http://localhost:8080",
            "unknown"
        );
    }

    /**
     * Full constructor with cooldown support.
     *
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param baseUrl Base URL for this Pantera instance
     */
    CachedProxySlice(
        final Slice remote,
        final Repository repo,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String baseUrl
    ) {
        this(remote, repo, cache, events, rname, rtype, cooldown, inspector, baseUrl, "unknown");
    }

    /**
     * Full constructor with upstream URL for metrics.
     *
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     * @param events Proxy artifact events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param baseUrl Base URL for this Pantera instance
     * @param upstreamUrl Upstream URL for metrics
     */
    CachedProxySlice(
        final Slice remote,
        final Repository repo,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String baseUrl,
        final String upstreamUrl
    ) {
        this.remote = remote;
        this.cache = cache;
        this.repo = repo;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.baseUrl = baseUrl;
        this.upstreamUrl = upstreamUrl;
        this.refreshing = ConcurrentHashMap.newKeySet();
        this.lastModifiedStore = new ConcurrentHashMap<>();
        final Storage storage = repo.storage();
        this.cacheWriter = storage == null
            ? null
            : new ProxyCacheWriter(storage, rname, meterRegistry());
        this.primarySingleFlight = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
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

            // WI-07 §9.5 — integrity-verified atomic primary+sidecar write on
            // cache-miss. Runs only when the request path resolves to a
            // primary dist archive (.zip / .tar / .phar). Metadata JSON
            // paths fall through to the existing flow unchanged.
            if (this.cacheWriter != null && isPrimaryArtifact(path)) {
                return this.verifyAndServePrimary(line, path);
            }

            // Keep ~dev suffix in cache key to avoid collision between stable and dev metadata
            final String name = path
                .replaceAll("^/p2?/", "")
                .replaceAll("\\^.*", "")
                .replaceAll(".json$", "");

            // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
            // This ensures offline mode works - serve cached content even when upstream is down
            return this.checkCacheFirst(line, name, headers);
        });
    }

    /**
     * Check cache first before evaluating cooldown. This ensures offline mode works -
     * cached content is served even when upstream/network is unavailable.
     *
     * @param line Request line
     * @param name Package name
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> checkCacheFirst(
        final RequestLine line,
        final String name,
        final Headers headers
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
                            this.backgroundRefresh(line, name, headers);
                        }
                        return this.serveCachedMetadata(bytes);
                    });
                });
            }
            // Cache MISS - now we need network, evaluate cooldown first
            return this.evaluateCooldownAndFetch(line, name, headers);
        }).toCompletableFuture();
    }

    /**
     * Serve cached metadata bytes: rewrite URLs if needed, build response.
     *
     * <p>Track 5 Phase 1A: cooldown re-evaluation removed from the cache-hit
     * path. Pre-Track 5, this method called {@link #evaluateMetadataCooldown}
     * which goes through {@code RegistryBackedInspector} and can fall
     * through to {@code PackagistSource} (network) when L1+L2 miss. That
     * made every cached metadata read dependent on packagist.org being
     * reachable AND inside its rate-limit budget. Cooldown still gates the
     * cache-miss / write-time refresh path inside
     * {@link #fetchThroughCache} — once metadata has been written, requests
     * for it serve from cache with zero upstream I/O.
     *
     * @param name Package name
     * @param headers Request headers (unused on cache-hit; kept for symmetry)
     * @param bytes Cached metadata bytes
     * @return Response future
     */
    private CompletableFuture<Response> serveCachedMetadata(final byte[] bytes) {
        final byte[] rewritten = this.rewriteMetadata(bytes);
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/json")
                .body(new Content.From(rewritten))
                .build()
        );
    }

    /**
     * Evaluate cooldown (if applicable) then fetch from upstream.
     * Only called when cache miss - requires network access.
     *
     * @param line Request line
     * @param name Package name
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> evaluateCooldownAndFetch(
        final RequestLine line,
        final String name,
        final Headers headers
    ) {
        // Check if this is a versioned package request that needs cooldown check
        final Optional<CooldownRequest> cooldownReq = this.parseCooldownRequest();

        if (cooldownReq.isPresent()) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Evaluating cooldown for package")
                .eventCategory("web")
                .eventAction("cooldown_check")
                .field("package.name", name)
                .field("log.source", "application")
                .log();
            return this.cooldown.evaluate(cooldownReq.get(), this.inspector)
                .thenCompose(result -> this.afterCooldown(result, line, name, headers));
        }

        return this.fetchThroughCache(line, name, headers);
    }

    /**
     * Handle response after cooldown evaluation.
     *
     * @param result Cooldown result
     * @param line Request line
     * @param name Package name
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> afterCooldown(
        final CooldownResult result,
        final RequestLine line,
        final String name,
        final Headers headers
    ) {
        if (result.blocked()) {
            EcsLogger.info("com.auto1.pantera.composer")
                .message("Cooldown blocked request")
                .eventCategory("web")
                .eventAction("cooldown_check")
                .eventOutcome("failure")
                .field("event.reason", "cooldown_active")
                .field("package.name", name)
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(
                CooldownResponseRegistry.instance()
                    .getOrThrow(this.rtype)
                    .forbidden(result.block().orElseThrow())
            );
        }
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Cooldown allowed request")
            .eventCategory("web")
            .eventAction("allowed")
            .eventOutcome("success")
            .field("package.name", name)
            .field("log.source", "application")
            .log();
        return this.fetchThroughCache(line, name, headers);
    }

    /**
     * Trigger background refresh of metadata (stale-while-revalidate pattern).
     * Serves stale content immediately while refreshing in background.
     *
     * @param line Request line
     * @param name Package name
     * @param headers Request headers
     */
    private void backgroundRefresh(
        final RequestLine line,
        final String name,
        final Headers headers
    ) {
        if (this.refreshing.add(name)) {
            CompletableFuture.runAsync(() -> {
                try {
                    this.fetchThroughCache(line, name, headers).join();
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
     * Fetch package through cache.
     *
     * @param line Request line
     * @param name Package name
     * @param headers Request headers
     * @return Response future
     */
    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final String name,
        final Headers headers
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
            // Content is already pre-rewritten at write time
            return pkgs.get().asBytesFuture().thenCompose(bytes ->
                this.evaluateMetadataCooldown(name, headers, bytes)
                    .thenCompose(result -> {
                        if (result.blocked()) {
                            EcsLogger.info("com.auto1.pantera.composer")
                                .message("Cooldown blocked metadata request")
                                .eventCategory("web")
                                .eventAction("cooldown_check")
                                .eventOutcome("failure")
                                .field("event.reason", "cooldown_active")
                                .field("package.name", name)
                                .field("log.source", "application")
                                .log();
                            return CompletableFuture.completedFuture(
                                CooldownResponseRegistry.instance()
                                    .getOrThrow(this.rtype)
                                    .forbidden(result.block().orElseThrow())
                            );
                        }
                        // Save rewritten metadata for ProxyDownloadSlice (original_url lookup)
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
                                return ResponseBuilder.ok()
                                    .header("Content-Type", "application/json")
                                    .body(new Content.From(bytes))
                                    .build();
                            });
                    })
            );
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

    private CompletableFuture<CooldownResult> evaluateMetadataCooldown(
        final String name, final Headers headers, final byte[] bytes
    ) {
        try {
            final javax.json.JsonObject json = javax.json.Json.createReader(new java.io.StringReader(new String(bytes))).readObject();

            // Handle both Satis format (packages is array) and traditional format (packages is object)
            final javax.json.JsonValue packagesValue = json.get("packages");
            if (packagesValue == null) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }

            // If packages is an array (Satis format), skip cooldown check
            // Satis format has empty packages array and uses provider-includes instead
            if (packagesValue.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Satis format detected (packages is array), skipping cooldown check")
                    .eventCategory("web")
                    .eventAction("cooldown_check")
                    .field("log.source", "application")
                    .log();
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }

            // Traditional format: packages is an object
            if (packagesValue.getValueType() != javax.json.JsonValue.ValueType.OBJECT) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }

            final javax.json.JsonObject packages = packagesValue.asJsonObject();
            final javax.json.JsonValue pkgVal = packages.get(name);
            if (pkgVal == null) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            final java.util.Optional<String> latest = latestVersion(pkgVal);
            if (latest.isEmpty()) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            final String owner = new Login(headers).getValue();
            final com.auto1.pantera.cooldown.api.CooldownRequest req = new com.auto1.pantera.cooldown.api.CooldownRequest(
                this.rtype,
                this.rname,
                name,
                latest.get(),
                owner,
                java.time.Instant.now()
            );
            return this.cooldown.evaluate(req, this.inspector);
        } catch (Exception e) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to parse metadata for cooldown check")
                .eventCategory("web")
                .eventAction("cooldown_check")
                .eventOutcome("failure")
                .error(e)
                .field("error.message", LogSanitizer.sanitizeMessage(e.getMessage()))
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
    }

    private static java.util.Optional<String> latestVersion(final javax.json.JsonValue pkgVal) {
        if (pkgVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
            final javax.json.JsonArray arr = pkgVal.asJsonArray();
            java.time.Instant best = null;
            String bestVer = null;
            for (javax.json.JsonValue v : arr) {
                final javax.json.JsonObject vo = v.asJsonObject();
                final String t = vo.getString("time", null);
                final String ver = vo.getString("version", null);
                if (t != null && ver != null) {
                    try {
                        final java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(t);
                        final java.time.Instant ins = odt.toInstant();
                        if (best == null || ins.isAfter(best)) {
                            best = ins;
                            bestVer = ver;
                        }
                    } catch (final Exception ex) {
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("Failed to parse Composer version time")
                            .error(ex)
                            .field("log.source", "application")
                            .log();
                    }
                }
            }
            return java.util.Optional.ofNullable(bestVer);
        } else {
            final javax.json.JsonObject versions = pkgVal.asJsonObject();
            java.time.Instant best = null;
            String bestVer = null;
            for (String key : versions.keySet()) {
                final javax.json.JsonObject vo = versions.getJsonObject(key);
                if (vo == null) {
                    continue;
                }
                final String t = vo.getString("time", null);
                if (t != null) {
                    try {
                        final java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(t);
                        final java.time.Instant ins = odt.toInstant();
                        if (best == null || ins.isAfter(best)) {
                            best = ins;
                            bestVer = key;
                        }
                    } catch (final Exception ex) {
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("Failed to parse Composer version time")
                            .error(ex)
                            .field("log.source", "application")
                            .log();
                    }
                }
            }
            return java.util.Optional.ofNullable(bestVer);
        }
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
     * Parse cooldown request from path if applicable.
     *
     * @return Optional cooldown request
     */
    private Optional<CooldownRequest> parseCooldownRequest() {
        // TODO: Implement version extraction from request context
        // For now, we'll need to fetch the metadata to get all versions
        // This is a simplified approach - in production you might want to optimize this
        // by caching version lists or parsing the request differently
        return Optional.empty();
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

    // ===== WI-07 §9.5: ProxyCacheWriter integration =====

    /**
     * Check if path represents a Composer primary artifact (zip / tar /
     * phar dist archive) that should be routed through
     * {@link ProxyCacheWriter}.
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
     * Primary-artifact flow (T-P07): if the cache already has the primary,
     * serve from the cache; otherwise tee bytes from the upstream body to
     * both the client and storage in a single pass via
     * {@link ProxyCacheWriter#streamThroughAndCommit}. Concurrent callers
     * for the same uncached primary collapse via
     * {@link #primarySingleFlight} — only the leader fires upstream;
     * followers wait then re-enter this method against the now-warm cache.
     *
     * <p>Trade-off vs. the legacy buffered path: integrity failures no
     * longer fail-closed for the in-flight client (bytes are already in
     * the response stream when the sidecar comparison runs) — the cache
     * stays empty so the next request re-fetches cleanly. Matches the
     * Maven primary-path decision.
     */
    private CompletableFuture<Response> verifyAndServePrimary(
        final RequestLine line, final String path
    ) {
        final Storage storage = this.repo.storage();
        final Key key = new Key.From(path.startsWith("/") ? path.substring(1) : path);
        return storage.exists(key).thenCompose(present -> {
            if (present) {
                return this.serveFromCache(storage, key);
            }
            // T-P07: single-flight the cache-miss leg. The leader streams
            // upstream → tee → client + storage; followers park on the gate
            // and re-enter verifyAndServePrimary which now hits the warm cache.
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
                .thenCompose(ignored -> this.verifyAndServePrimary(line, path));
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Composer primary-artifact verify-and-serve failed; returning 502")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("repository.name", this.rname)
                .field("url.path", path)
                .error(err)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badGateway().build();
        }).toCompletableFuture();
    }

    /**
     * Stream-through primary fetch (T-P07): tee upstream body bytes to the
     * client and the cache temp file in a single pass via
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
            this.rname,
            path
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> this.fetchSidecar(line, ".sha256"));
        return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
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
                    // Composer's only sidecar is .sha256, which lives in
                    // NON_BLOCKING_DEFAULT; pass an empty non-blocking set so
                    // the verification keeps the cache empty on mismatch.
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
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Composer stream-through primary fetch failed; returning 502")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.rname)
                    .field("url.path", path)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.badGateway()
                    .textBody("Upstream temporarily unavailable")
                    .build();
            });
    }

    /**
     * Fetch a sidecar for the primary at {@code line}. Returns
     * {@link Optional#empty()} for 4xx/5xx and I/O errors so the writer
     * treats the sidecar as absent; a transient sidecar failure never
     * blocks the primary write.
     */
    private CompletionStage<Optional<InputStream>> fetchSidecar(
        final RequestLine primary, final String extension
    ) {
        final String sidecarPath = primary.uri().getPath() + extension;
        final RequestLine sidecarLine = new RequestLine(RqMethod.GET, sidecarPath);
        return this.remote.response(sidecarLine, Headers.EMPTY, Content.EMPTY)
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
    private CompletionStage<Response> serveFromCache(final Storage storage, final Key key) {
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
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("MicrometerMetrics registry unavailable; writer will run without metrics")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
        return null;
    }

}
