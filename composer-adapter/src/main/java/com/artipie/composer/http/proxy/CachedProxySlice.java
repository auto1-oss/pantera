/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.cache.Cache;
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
import com.jcabi.log.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
     * @param remote Remote slice
     * @param repo Repository
     * @param cache Cache
     */
    CachedProxySlice(Slice remote, Repository repo, Cache cache) {
        this(remote, repo, cache, Optional.empty(), "composer", "php",
            com.artipie.cooldown.NoopCooldownService.INSTANCE,
            new NoopComposerCooldownInspector(),
            "http://localhost:8080"
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
        this.remote = remote;
        this.cache = cache;
        this.repo = repo;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.baseUrl = baseUrl;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        Logger.info(this, "Composer proxy request: %s", path);
        
        // Keep ~dev suffix in cache key to avoid collision between stable and dev metadata
        final String name = path
            .replaceAll("^/p2?/", "")
            .replaceAll("\\^.*", "")
            .replaceAll(".json$", "");
        
        // Check if this is a versioned package request that needs cooldown check
        final Optional<CooldownRequest> cooldownReq = this.parseCooldownRequest(path, headers);
        
        if (cooldownReq.isPresent()) {
            Logger.info(this, "Evaluating cooldown for %s", name);
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
            Logger.info(
                this,
                "Cooldown BLOCKED request for %s (reason: %s)",
                name,
                result.block().orElseThrow().reason()
            );
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        Logger.debug(this, "Cooldown ALLOWED request for %s", name);
        return this.fetchThroughCache(line, name, headers);
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
                        this.packageFromRemote(line, headers),
                        (lcl, rmt) -> new MergePackage.WithRemote(packageName, lcl).merge(rmt)
                    ).thenCompose(Function.identity())
                    .thenApply(content -> {
                        // Note: Do NOT emit events here - this is just metadata
                        // Events should only be emitted when actual zip files are downloaded
                        Logger.info(
                            this,
                            "Fetched package metadata from remote: %s, content present: %s",
                            name,
                            content.isPresent()
                        );
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
                            Logger.info(this, "Cooldown BLOCKED metadata for %s", name);
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
                                    Logger.debug(this, "Saved metadata to storage: %s", metadataKey);
                                    return ResponseBuilder.ok()
                                        .header("Content-Type", "application/json")
                                        .body(new Content.From(rewrittenBytes))
                                        .build();
                                });
                        });
                    })
            );
        }).exceptionally(throwable -> {
            Logger.warn(this, "Failed to read cached item: %[exception]s", throwable);
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
                Logger.debug(this, "Satis format detected (packages is array), skipping cooldown check");
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
            Logger.warn(this, "Failed to parse metadata for cooldown check: %s", e.getMessage());
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
        Logger.debug(this, "Rewriting metadata URLs to proxy through Artipie (base URL: %s)", this.baseUrl);
        try {
            final String json = new String(original, java.nio.charset.StandardCharsets.UTF_8);
            final MetadataUrlRewriter rewriter = new MetadataUrlRewriter(this.baseUrl);
            final byte[] rewritten = rewriter.rewrite(json);
            return new Content.From(rewritten);
        } catch (Exception ex) {
            Logger.error(this, "Failed to rewrite metadata: %[exception]s", ex);
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
            Logger.warn(this, "Events queue is empty, cannot emit event for %s", name);
            return;
        }
        if (content.isEmpty()) {
            Logger.warn(this, "Content is empty, cannot emit event for %s", name);
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
        Logger.info(
            this,
            "Added Composer proxy event for %s (owner=%s, queue_size=%d)",
            name,
            owner,
            this.events.get().size()
        );
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
        return new Remote.WithErrorHandling(
            () -> this.remote.response(line, Headers.EMPTY, Content.EMPTY)
                .thenApply(response -> {
                    Logger.debug(
                        this,
                        "Remote response for %s: status=%s",
                        line.uri().getPath(),
                        response.status()
                    );
                    if (response.status().success()) {
                        return Optional.of(response.body());
                    }
                    Logger.warn(
                        this,
                        "Remote returned non-success status for %s: %s",
                        line.uri().getPath(),
                        response.status()
                    );
                    return Optional.empty();
                })
        ).get();
    }
}
