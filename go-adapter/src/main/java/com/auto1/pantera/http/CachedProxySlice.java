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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.DigestVerification;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.cooldown.CooldownRequest;
import com.auto1.pantera.cooldown.CooldownResponses;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Go proxy slice with cache support.
 *
 * @since 1.0
 */
final class CachedProxySlice implements Slice {

    /**
     * Checksum header pattern.
     */
    private static final Pattern CHECKSUM_PATTERN =
        Pattern.compile("x-checksum-(sha1|sha256|sha512|md5)", Pattern.CASE_INSENSITIVE);

    /**
     * Translation of checksum headers to digest algorithms.
     */
    private static final Map<String, String> DIGEST_NAMES = Map.of(
        "sha1", "SHA-1",
        "sha256", "SHA-256",
        "sha512", "SHA-512",
        "md5", "MD5"
    );

    /**
     * Pattern to match Go module artifacts.
     * Matches: module/path/@v/v1.2.3.{info|mod|zip}
     */
    private static final Pattern ARTIFACT = Pattern.compile(
        "^(?<module>.+)/@v/v(?<version>[^/]+)\\.(?<ext>info|mod|zip)$"
    );

    /**
     * Origin slice.
     */
    private final Slice client;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * Proxy artifact events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type.
     */
    private final String rtype;

    /**
     * Optional storage for TTL-based metadata cache.
     */
    private final Optional<Storage> storage;

    /**
     * Wraps origin slice with caching layer and default 12h metadata TTL.
     *
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param storage Optional storage for TTL-based metadata cache
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final Optional<Storage> storage,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector
    ) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.storage = storage;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        EcsLogger.info("com.auto1.pantera.go")
            .message("Processing Go proxy request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("url.path", path)
            .field("repository.name", this.rname)
            .log();

        if ("/".equals(path) || path.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Handling root path")
                .eventCategory("web")
                .eventAction("proxy_request")
                .log();
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);
        final Matcher matcher = ARTIFACT.matcher(key.string());

        // For non-artifact paths (e.g., list endpoints), skip cooldown and cache directly
        if (!matcher.matches()) {
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Non-artifact path, skipping cooldown")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return this.fetchThroughCache(line, key, headers, Optional.empty(), Optional.empty());
        }

        // Extract artifact info and create cooldown request
        final String module = matcher.group("module");
        final String version = matcher.group("version");
        final String user = new Login(headers).getValue();
        EcsLogger.debug("com.auto1.pantera.go")
            .message("Go artifact request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("package.name", module)
            .field("package.version", version)
            .field("user.name", user)
            .log();

        // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
        // This ensures offline mode works - serve cached content even when upstream is down
        return this.cache.load(
            key,
            Remote.EMPTY,  // Just check cache existence
            CacheControl.Standard.ALWAYS
        ).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT - serve immediately without any network calls
                EcsLogger.info("com.auto1.pantera.go")
                    .message("Cache hit, serving cached artifact (offline-safe)")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", module)
                    .field("package.version", version)
                    .log();
                // Record event for .zip files (with unknown release date since we skip network)
                if (key.string().endsWith(".zip")) {
                    this.enqueueEvent(key, user, Optional.of(module + "/@v/" + version), Optional.empty());
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(cached.get())
                        .build()
                );
            }

            // Cache MISS - now we need network, evaluate cooldown
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Cache miss, evaluating cooldown")
                .eventCategory("web")
                .eventAction("cache_miss")
                .eventOutcome("success")
                .field("package.name", module)
                .field("package.version", version)
                .log();

            final CooldownRequest request = new CooldownRequest(
                this.rtype,
                this.rname,
                module,
                version,
                user,
                Instant.now()
            );

            return this.cooldown.evaluate(request, this.inspector)
                .thenCompose(result -> {
                    if (result.blocked()) {
                        EcsLogger.info("com.auto1.pantera.go")
                            .message("Blocked Go artifact due to cooldown: " + result.block().orElseThrow().reason())
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .field("event.reason", "cooldown_active")
                            .field("package.name", module)
                            .field("package.version", version)
                            .log();
                        return CompletableFuture.completedFuture(
                            CooldownResponses.forbidden(result.block().orElseThrow())
                        );
                    }
                    EcsLogger.debug("com.auto1.pantera.go")
                        .message("Cooldown passed, proceeding with fetch")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .field("package.name", module)
                        .field("package.version", version)
                        .log();
                    // Cooldown passed, proceed with fetch
                    // Get the release date for database event
                    return this.inspector.releaseDate(module, version)
                        .thenCompose(releaseDate -> {
                            EcsLogger.debug("com.auto1.pantera.go")
                                .message("Release date retrieved")
                                .eventCategory("web")
                                .eventAction("proxy_request")
                                .field("package.name", module)
                                .field("package.version", version)
                                .field("package.release_date", releaseDate.orElse(null))
                                .log();
                            return this.fetchFromRemoteAndCache(
                                line,
                                key,
                                user,
                                Optional.of(module + "/@v/" + version),
                                releaseDate,
                                new AtomicReference<>(Headers.EMPTY)
                            );
                        });
                });
        }).toCompletableFuture();
    }


    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate
    ) {
        final AtomicReference<Headers> rshdr = new AtomicReference<>(Headers.EMPTY);
        final String owner = new Login(request).getValue();

        // CRITICAL FIX: Check cache FIRST before attempting remote HEAD
        // This allows serving cached content when upstream is unavailable (offline mode)
        // Previously, the code would fail immediately if remote HEAD failed, even if
        // the content was already cached locally.
        return this.cache.load(
            key,
            Remote.EMPTY,  // Just check cache, don't fetch yet
            CacheControl.Standard.ALWAYS
        ).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT - serve immediately without contacting remote
                EcsLogger.debug("com.auto1.pantera.go")
                    .message("Cache hit, serving cached content")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", key.string())
                    .log();
                // Record event for .zip files
                if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                    this.enqueueEvent(key, owner, artifactPath, releaseDate);
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(cached.get())
                        .build()
                );
            }
            // Cache MISS - fetch from remote with checksum validation
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Cache miss, fetching from remote")
                .eventCategory("web")
                .eventAction("cache_miss")
                .eventOutcome("success")
                .field("package.name", key.string())
                .log();
            return this.fetchFromRemoteAndCache(line, key, owner, artifactPath, releaseDate, rshdr);
        }).toCompletableFuture();
    }

    /**
     * Fetch content from remote and cache it.
     * Called when cache miss occurs.
     *
     * @param line Request line
     * @param key Cache key
     * @param owner Owner username
     * @param artifactPath Optional artifact path for events
     * @param releaseDate Optional release date
     * @param rshdr Atomic reference to store response headers
     * @return Response future
     */
    private CompletableFuture<Response> fetchFromRemoteAndCache(
        final RequestLine line,
        final Key key,
        final String owner,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate,
        final AtomicReference<Headers> rshdr
    ) {
        // Get checksum headers from remote HEAD for validation
        return new RepoHead(this.client)
            .head(line.uri().getPath())
            .exceptionally(err -> {
                // Network error during HEAD - log and continue with empty headers
                // This allows cache to work in degraded mode (no checksum validation)
                EcsLogger.warn("com.auto1.pantera.go")
                    .message("Remote HEAD failed, proceeding without checksum validation")
                    .eventCategory("web")
                    .eventAction("proxy_request")
                    .eventOutcome("success")
                    .field("event.reason", "degraded_response")
                    .field("package.name", key.string())
                    .field("error.message", err.getMessage())
                    .log();
                return Optional.empty();
            })
            .thenCompose(head -> this.cache.load(
                key,
                new Remote.WithErrorHandling(
                    () -> {
                        final CompletableFuture<Optional<? extends Content>> promise =
                            new CompletableFuture<>();
                        this.client.response(line, Headers.EMPTY, Content.EMPTY)
                            .thenApply(resp -> {
                                final CompletableFuture<Void> term = new CompletableFuture<>();
                                if (resp.status().success()) {
                                    final Flowable<ByteBuffer> res =
                                        Flowable.fromPublisher(resp.body())
                                            .doOnError(term::completeExceptionally)
                                            .doOnTerminate(() -> term.complete(null));
                                    promise.complete(Optional.of(new Content.From(res)));
                                } else {
                                    // CRITICAL: Consume body to prevent Vert.x request leak
                                    resp.body().asBytesFuture().whenComplete((ignored, error) -> {
                                        promise.complete(Optional.empty());
                                        term.complete(null);
                                    });
                                }
                                rshdr.set(resp.headers());
                                return term;
                            })
                            .exceptionally(err -> {
                                // Network error during fetch - complete with empty
                                EcsLogger.warn("com.auto1.pantera.go")
                                    .message("Remote fetch failed")
                                    .eventCategory("web")
                                    .eventAction("proxy_request")
                                    .eventOutcome("failure")
                                    .field("package.name", key.string())
                                    .field("error.message", err.getMessage())
                                    .log();
                                promise.complete(Optional.empty());
                                return null;
                            });
                        return promise;
                    }
                ),
                this.cacheControlFor(key, head.orElse(Headers.EMPTY))
            )).handle(
                (content, throwable) -> {
                    if (throwable == null && content.isPresent()) {
                        // Record database event ONLY after successful cache load for .zip files
                        if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                            EcsLogger.debug("com.auto1.pantera.go")
                                .message("Attempting to enqueue Go proxy event")
                                .eventCategory("web")
                                .eventAction("proxy_request")
                                .field("package.name", key.string())
                                .field("file.path", artifactPath.get())
                                .field("user.name", owner)
                                .log();
                            this.enqueueEvent(
                                key,
                                owner,
                                artifactPath,
                                releaseDate.or(() -> this.parseLastModified(rshdr.get()))
                            );
                        }
                        return ResponseBuilder.ok()
                            .headers(rshdr.get())
                            .body(content.get())
                            .build();
                    }
                    if (throwable != null) {
                        EcsLogger.error("com.auto1.pantera.go")
                            .message("Failed to fetch through cache")
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .error(throwable)
                            .log();
                    } else {
                        EcsLogger.warn("com.auto1.pantera.go")
                            .message("Cache load returned empty, returning 404")
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .field("event.reason", "artifact_not_found")
                            .field("package.name", key.string())
                            .field("repository.name", this.rname)
                            .log();
                    }
                    return ResponseBuilder.notFound().build();
                }
            ).toCompletableFuture();
    }


    /**
     * Parse Last-Modified header to Instant.
     *
     * @param headers Response headers
     * @return Optional Instant
     */
    private Optional<Instant> parseLastModified(final Headers headers) {
        try {
            return StreamSupport.stream(headers.spliterator(), false)
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)));
        } catch (final DateTimeParseException ex) {
            EcsLogger.warn("com.auto1.pantera.go")
                .message("Failed to parse Last-Modified header: " + ex.getParsedString())
                .eventCategory("web")
                .eventAction("header_parse")
                .eventOutcome("failure")
                .log();
            return Optional.empty();
        }
    }

    /**
     * Enqueue artifact event for metadata processing.
     * Only enqueues for actual artifacts (not list endpoints).
     *
     * @param key Artifact key
     * @param owner Owner username
     * @param artifactPath Optional artifact path (module/@v/version)
     * @param releaseDate Optional release date
     */
    private void enqueueEvent(
        final Key key,
        final String owner,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate
    ) {
        // Only enqueue if this is an actual artifact (has artifactPath)
        if (artifactPath.isEmpty()) {
            return;
        }
        this.addEventToQueue(
            new Key.From(artifactPath.get()),
            owner,
            releaseDate.map(Instant::toEpochMilli)
        );
    }

    /**
     * Add event to queue for background processing.
     * The event will be processed by GoProxyPackageProcessor to write metadata to database.
     *
     * @param key Artifact key (should be in format: module/@v/version)
     * @param owner Owner username
     * @param release Optional release timestamp in millis
     */
    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isEmpty()) {
            EcsLogger.error("com.auto1.pantera.go")
                .message("Events queue is NOT present - cannot enqueue events")
                .eventCategory("web")
                .eventAction("proxy_request")
                .eventOutcome("failure")
                .log();
            return;
        }

        this.events.ifPresent(queue -> {
            final ProxyArtifactEvent event = new ProxyArtifactEvent(
                key,
                this.rname,
                owner,
                release
            );
            // Bounded ProxyArtifactEvent queue — offer() + drop counter so
            // a full queue cannot cascade to 503 on the serve path.
            if (!queue.offer(event)) {
                com.auto1.pantera.metrics.EventsQueueMetrics.recordDropped(this.rname);
            } else {
                EcsLogger.debug("com.auto1.pantera.go")
                    .message("Successfully enqueued Go proxy event (queue size: " + queue.size() + ")")
                    .eventCategory("web")
                    .eventAction("proxy_request")
                    .field("package.name", key.string())
                    .field("repository.name", this.rname)
                    .field("user.name", owner)
                    .field("package.release_date", release.map(Object::toString).orElse(null))
                    .log();
            }
        });
    }

    /**
     * Determine cache control strategy for the given key.
     * Uses TTL-based control for metadata paths (list, @latest),
     * checksum-based control for artifacts.
     *
     * @param key Cache key
     * @param head Headers from HEAD request
     * @return Cache control strategy
     */
    private CacheControl cacheControlFor(final Key key, final Headers head) {
        final String path = key.string();
        // Metadata paths need TTL-based expiration to pick up new versions
        if (this.isMetadataPath(path)) {
            return this.storage
                .map(sto -> (CacheControl) new CacheTimeControl(sto))
                .orElse(CacheControl.Standard.ALWAYS);
        }
        // Artifacts use checksum-based validation
        return new CacheControl.All(
            StreamSupport.stream(head.spliterator(), false)
                .map(Header::new)
                .map(CachedProxySlice::checksumControl)
                .toList()
        );
    }

    /**
     * Check if path is a metadata path that needs TTL-based caching.
     * Metadata paths: @v/list (version list), @latest (latest version info)
     *
     * @param path Request path
     * @return true if metadata path
     */
    private boolean isMetadataPath(final String path) {
        return path.endsWith("/@v/list") || path.endsWith("/@latest");
    }

    private static CacheControl checksumControl(final Header header) {
        final Matcher matcher = CachedProxySlice.CHECKSUM_PATTERN.matcher(header.getKey());
        final CacheControl res;
        if (matcher.matches()) {
            try {
                res = new DigestVerification(
                    new Digests.FromString(
                        CachedProxySlice.DIGEST_NAMES.get(
                            matcher.group(1).toLowerCase(Locale.US)
                        )
                    ).get(),
                    Hex.decodeHex(header.getValue().toCharArray())
                );
            } catch (final DecoderException err) {
                throw new IllegalStateException("Invalid digest hex", err);
            }
        } else {
            res = CacheControl.Standard.ALWAYS;
        }
        return res;
    }

    private CompletableFuture<Response> handleRootPath(final RequestLine line) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (resp.status().success()) {
                    return ResponseBuilder.ok()
                        .headers(resp.headers())
                        .body(resp.body())
                        .build();
                }
                return ResponseBuilder.notFound().build();
            });
    }
}
