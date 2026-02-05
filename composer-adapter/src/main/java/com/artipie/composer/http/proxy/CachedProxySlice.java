/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.log.LogSanitizer;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.FromStorageCache;
import com.artipie.asto.cache.Remote;
import com.artipie.composer.JsonPackages;
import com.artipie.composer.Packages;
import com.artipie.composer.Repository;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.scheduling.ProxyArtifactEvent;

import com.artipie.cache.DistributedInFlight;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composer proxy slice with cache support, cooldown service, and event emission.
 */
@SuppressWarnings({"PMD.UnusedPrivateField", "PMD.SingularField"})
final class CachedProxySlice implements Slice {

    /**
     * Pattern to extract package name and version from path.
     * Matches /p2/vendor/package.json
     */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
        "^/p2?/(?<name>[^/]+/[^/~^]+?)(?:~.*|\\^.*|\\.json)?$"
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
     * Distributed in-flight tracker for request deduplication.
     * Uses Pub/Sub for instant notification across cluster nodes.
     */
    private final DistributedInFlight inFlight;

    /**
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     */
    CachedProxySlice(Slice remote, Repository repo, Cache cache) {
        this(remote, repo, cache, Optional.empty(), "composer", "php",
            com.artipie.cooldown.NoopCooldownService.INSTANCE,
            new NoopComposerCooldownInspector(),
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
     * @param baseUrl Base URL for this Artipie instance
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
     * @param baseUrl Base URL for this Artipie instance
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
        // Distributed in-flight with Pub/Sub for cluster-wide deduplication
        this.inFlight = new DistributedInFlight(rtype + ":" + rname);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String path = line.uri().getPath();
            EcsLogger.info("com.artipie.composer")
                .message("Composer proxy request")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();

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
        // Use FromStorageCache directly to avoid FromRemoteCache issues with Remote.EMPTY
        return new FromStorageCache(this.repo.storage()).load(
            new Key.From(name),
            Remote.EMPTY,
            CacheControl.Standard.ALWAYS
        ).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT - serve immediately without any network calls
                EcsLogger.info("com.artipie.composer")
                    .message("Cache hit, serving cached metadata (offline-safe)")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .eventOutcome("cache_hit")
                    .field("package.name", name)
                    .log();
                // Read cached bytes and rewrite URLs
                return cached.get().asBytesFuture().thenCompose(bytes ->
                    this.evaluateMetadataCooldown(name, headers, bytes)
                        .thenCompose(result -> {
                            if (result.blocked()) {
                                EcsLogger.info("com.artipie.composer")
                                    .message("Cooldown blocked cached metadata request")
                                    .eventCategory("repository")
                                    .eventAction("cooldown_check")
                                    .eventOutcome("blocked")
                                    .field("package.name", name)
                                    .log();
                                return CompletableFuture.completedFuture(
                                    CooldownResponses.forbidden(result.block().orElseThrow())
                                );
                            }
                            // Rewrite URLs in cached metadata
                            final Content rewritten = this.rewriteMetadata(bytes, headers);
                            return rewritten.asBytesFuture().thenApply(rewrittenBytes ->
                                ResponseBuilder.ok()
                                    .header("Content-Type", "application/json")
                                    .body(new Content.From(rewrittenBytes))
                                    .build()
                            );
                        })
                );
            }
            // Cache MISS - now we need network, evaluate cooldown first
            return this.evaluateCooldownAndFetch(line, name, headers);
        }).toCompletableFuture();
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
        final String path = line.uri().getPath();
        // Check if this is a versioned package request that needs cooldown check
        final Optional<CooldownRequest> cooldownReq = this.parseCooldownRequest(path, headers);

        if (cooldownReq.isPresent()) {
            EcsLogger.debug("com.artipie.composer")
                .message("Evaluating cooldown for package")
                .eventCategory("repository")
                .eventAction("cooldown_check")
                .field("package.name", name)
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
            EcsLogger.info("com.artipie.composer")
                .message("Cooldown blocked request")
                .eventCategory("repository")
                .eventAction("cooldown_check")
                .eventOutcome("blocked")
                .field("package.name", name)
                .field("event.reason", result.block().orElseThrow().reason())
                .log();
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        EcsLogger.debug("com.artipie.composer")
            .message("Cooldown allowed request")
            .eventCategory("repository")
            .eventAction("cooldown_check")
            .eventOutcome("allowed")
            .field("package.name", name)
            .log();
        return this.fetchThroughCache(line, name, headers);
    }
    
    /**
     * Fetch package through cache with distributed request deduplication.
     * Uses DistributedInFlight with Pub/Sub for cluster-wide coordination.
     * Only one node fetches from upstream; others wait and read from cache.
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
        return this.inFlight.tryAcquire(name)
            .thenCompose(result -> {
                if (result.isLeader()) {
                    // We are the leader - fetch from upstream
                    return this.doFetchThroughCache(line, name, headers)
                        .orTimeout(90, TimeUnit.SECONDS)
                        .whenComplete((response, error) -> {
                            // Signal completion to waiters via Pub/Sub
                            final boolean success = error == null && response != null
                                && response.status().success();
                            result.complete(success);
                        });
                } else {
                    // We are a waiter - wait for leader via Pub/Sub, then read from cache
                    return result.waitForLeader()
                        .thenCompose(success -> this.serveFromCacheForWaiter(name, success, headers));
                }
            });
    }

    /**
     * Serve content from storage cache for waiting requests.
     * Called after leader completes - reads from storage instead of re-fetching.
     *
     * @param name Package name
     * @param leaderSuccess True if leader succeeded (content should be in cache)
     * @param headers Request headers for cooldown evaluation
     * @return Response future with content from storage or fallback
     */
    private CompletableFuture<Response> serveFromCacheForWaiter(
        final String name,
        final boolean leaderSuccess,
        final Headers headers
    ) {
        if (!leaderSuccess) {
            // Leader failed - return not found
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
        // Read fresh content from storage cache (rewritten metadata was saved to name.json)
        final Key metadataKey = new Key.From(name + ".json");
        return this.repo.storage().exists(metadataKey).thenCompose(exists -> {
            if (exists) {
                return this.repo.storage().value(metadataKey).thenCompose(content ->
                    content.asBytesFuture().thenCompose(bytes ->
                        this.evaluateMetadataCooldown(name, headers, bytes)
                            .thenApply(result -> {
                                if (result.blocked()) {
                                    return CooldownResponses.forbidden(result.block().orElseThrow());
                                }
                                return ResponseBuilder.ok()
                                    .header("Content-Type", "application/json")
                                    .body(new Content.From(bytes))
                                    .build();
                            })
                    )
                );
            }
            // Fallback to raw cache if rewritten metadata not found
            return new FromStorageCache(this.repo.storage()).load(
                new Key.From(name),
                Remote.EMPTY,
                CacheControl.Standard.ALWAYS
            ).thenCompose(cached -> {
                if (cached.isPresent()) {
                    return cached.get().asBytesFuture().thenCompose(bytes -> {
                        final Content rewritten = this.rewriteMetadata(bytes, headers);
                        return rewritten.asBytesFuture().thenApply(rewrittenBytes ->
                            ResponseBuilder.ok()
                                .header("Content-Type", "application/json")
                                .body(new Content.From(rewrittenBytes))
                                .build()
                        );
                    });
                }
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }).toCompletableFuture();
        });
    }

    /**
     * Internal method that performs the actual fetch through cache.
     * Should only be called through fetchThroughCache for deduplication.
     */
    private CompletableFuture<Response> doFetchThroughCache(
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
                        this.packageFromRemote(line, headers),
                        (lcl, rmt) -> new MergePackage.WithRemote(packageName, lcl).merge(rmt)
                    ).thenCompose(Function.identity())
                    .thenApply(content -> {
                        // Note: Do NOT emit events here - this is just metadata
                        // Events should only be emitted when actual zip files are downloaded
                        EcsLogger.debug("com.artipie.composer")
                            .message("Fetched package metadata from remote (content present: " + content.isPresent() + ")")
                            .eventCategory("repository")
                            .eventAction("metadata_fetch")
                            .field("package.name", name)
                            .log();
                        return content;
                    })
            ),
            new CacheTimeControl(this.repo.storage())
        ).thenCompose((java.util.Optional<? extends Content> pkgs) -> {
            if (pkgs.isEmpty()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            // Read once and reuse for cooldown + rewrite to avoid OneTimePublisher double-consumption
            return pkgs.get().asBytesFuture().thenCompose(bytes ->
                this.evaluateMetadataCooldown(name, headers, bytes)
                    .thenCompose(result -> {
                        if (result.blocked()) {
                            EcsLogger.info("com.artipie.composer")
                                .message("Cooldown blocked metadata request")
                                .eventCategory("repository")
                                .eventAction("cooldown_check")
                                .eventOutcome("blocked")
                                .field("package.name", name)
                                .log();
                            return CompletableFuture.completedFuture(
                                CooldownResponses.forbidden(result.block().orElseThrow())
                            );
                        }
                        // Rewrite URLs in metadata to proxy through Artipie
                        final Content rewritten = this.rewriteMetadata(bytes, headers);
                        
                        // Save rewritten metadata to storage so ProxyDownloadSlice can find original URLs
                        final Key metadataKey = new Key.From(name + ".json");
                        return rewritten.asBytesFuture().thenCompose(rewrittenBytes -> {
                            final Content saved = new Content.From(rewrittenBytes);
                            return this.repo.storage().save(metadataKey, saved)
                                .thenApply(ignored -> {
                                    EcsLogger.debug("com.artipie.composer")
                                        .message("Saved metadata to storage")
                                        .eventCategory("repository")
                                        .eventAction("metadata_save")
                                        .field("package.name", metadataKey.string())
                                        .log();
                                    return ResponseBuilder.ok()
                                        .header("Content-Type", "application/json")
                                        .body(new Content.From(rewrittenBytes))
                                        .build();
                                });
                        });
                    })
            );
        }).exceptionally(throwable -> {
            EcsLogger.warn("com.artipie.composer")
                .message("Failed to read cached item")
                .eventCategory("repository")
                .eventAction("cache_read")
                .eventOutcome("failure")
                .field("error.message", throwable.getMessage())
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
                EcsLogger.debug("com.artipie.composer")
                    .message("Satis format detected (packages is array), skipping cooldown check")
                    .eventCategory("repository")
                    .eventAction("cooldown_check")
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
            final com.artipie.cooldown.CooldownRequest req = new com.artipie.cooldown.CooldownRequest(
                this.rtype,
                this.rname,
                name,
                latest.get(),
                owner,
                java.time.Instant.now()
            );
            return this.cooldown.evaluate(req, this.inspector);
        } catch (Exception e) {
            EcsLogger.warn("com.artipie.composer")
                .message("Failed to parse metadata for cooldown check")
                .eventCategory("repository")
                .eventAction("cooldown_check")
                .eventOutcome("failure")
                .field("error.message", LogSanitizer.sanitizeMessage(e.getMessage()))
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
                    } catch (Exception ignored) {
                        // ignore unparsable times
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
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
            return java.util.Optional.ofNullable(bestVer);
        }
    }
    
    /**
     * Rewrite metadata content to proxy downloads through Artipie.
     *
     * @param content Original metadata content
     * @param headers Request headers (unused, kept for signature compatibility)
     * @return Rewritten metadata content
     */
    private Content rewriteMetadata(final byte[] original, final Headers headers) {
        EcsLogger.debug("com.artipie.composer")
            .message("Rewriting metadata URLs to proxy through Artipie")
            .eventCategory("repository")
            .eventAction("metadata_rewrite")
            .field("url.path", this.baseUrl)
            .log();
        try {
            final String json = new String(original, java.nio.charset.StandardCharsets.UTF_8);
            final MetadataUrlRewriter rewriter = new MetadataUrlRewriter(this.baseUrl);
            final byte[] rewritten = rewriter.rewrite(json);
            return new Content.From(rewritten);
        } catch (Exception ex) {
            EcsLogger.error("com.artipie.composer")
                .message("Failed to rewrite metadata")
                .eventCategory("repository")
                .eventAction("metadata_rewrite")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return new Content.From(original);
        }
    }

    /**
     * Parse cooldown request from path if applicable.
     *
     * @param path Request path
     * @param headers Request headers
     * @return Optional cooldown request
     */
    private Optional<CooldownRequest> parseCooldownRequest(final String path, final Headers headers) {
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
            EcsLogger.warn("com.artipie.composer")
                .message("Events queue is empty, cannot emit event")
                .eventCategory("repository")
                .eventAction("event_creation")
                .eventOutcome("failure")
                .field("package.name", name)
                .log();
            return;
        }
        if (content.isEmpty()) {
            EcsLogger.warn("com.artipie.composer")
                .message("Content is empty, cannot emit event")
                .eventCategory("repository")
                .eventAction("event_creation")
                .eventOutcome("failure")
                .field("package.name", name)
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
        EcsLogger.info("com.artipie.composer")
            .message("Added Composer proxy event (queue size: " + this.events.get().size() + ")")
            .eventCategory("repository")
            .eventAction("event_creation")
            .eventOutcome("success")
            .field("package.name", name)
            .field("user.name", owner)
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
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Obtains info about package from remote.
     * @param line The request line (usually like this `GET /p2/vendor/package.json HTTP_1_1`)
     * @param headers Request headers
     * @return Content from respond of remote. If there were some errors,
     *  empty will be returned.
     */
    private CompletionStage<Optional<? extends Content>> packageFromRemote(
        final RequestLine line,
        final Headers headers
    ) {
        final long startTime = System.currentTimeMillis();
        return new Remote.WithErrorHandling(
            () -> {
                try {
                    return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
                        .thenCompose(response -> {
                            final long duration = System.currentTimeMillis() - startTime;
                            EcsLogger.debug("com.artipie.composer")
                                .message("Remote response received")
                                .eventCategory("repository")
                                .eventAction("remote_fetch")
                                .field("url.path", line.uri().getPath())
                                .field("http.response.status_code", response.status().code())
                                .log();
                            if (response.status().success()) {
                                this.recordProxyMetric("success", duration);
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
                                EcsLogger.warn("com.artipie.composer")
                                    .message("Remote returned non-success status")
                                    .eventCategory("repository")
                                    .eventAction("remote_fetch")
                                    .eventOutcome("failure")
                                    .field("url.path", line.uri().getPath())
                                    .field("http.response.status_code", response.status().code())
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
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
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
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
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
