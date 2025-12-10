/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.Remote;
import com.artipie.asto.ext.Digests;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.cache.CachedArtifactMetadataStore;
import com.artipie.http.cache.NegativeCache;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import org.apache.commons.codec.binary.Hex;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;

/**
 * Gradle proxy slice with cache support.
 *
 * @since 1.0
 */
final class CachedProxySlice implements Slice {

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
     * Metadata store for cached responses.
     */
    private final Optional<CachedArtifactMetadataStore> metadata;

    /**
     * True when cache is backed by persistent storage.
     */
    private final boolean storageBacked;

    /**
     * Negative cache for 404 responses.
     */
    private final NegativeCache negativeCache;

    /**
     * In-flight requests map for deduplication (prevents thundering herd).
     */
    private final Map<Key, CompletableFuture<Response>> inFlight = new ConcurrentHashMap<>();

    /**
     * Wraps origin slice with caching layer.
     *
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final Optional<Storage> storage
    ) {
        this(client, cache, events, rname, rtype, cooldown, inspector, storage,
            java.time.Duration.ofHours(24), true);
    }

    /**
     * Wraps origin slice with caching layer including negative cache.
     *
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final Optional<Storage> storage,
        final java.time.Duration negativeCacheTtl,
        final boolean negativeCacheEnabled
    ) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
        this.storageBacked = this.metadata.isPresent() && !Objects.equals(this.cache, Cache.NOP);
        this.negativeCache = new NegativeCache(
            negativeCacheTtl,
            negativeCacheEnabled,
            50_000,  // default max size
            null,    // use global Valkey config
            rtype,   // Repository type for cache key namespacing
            rname    // CRITICAL: Include repo name for cache isolation
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        EcsLogger.debug("com.artipie.gradle")
            .message("Gradle proxy request")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("url.path", path)
            .log();
        if ("/".equals(path) || path.isEmpty()) {
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);

        // Check negative cache first (404s)
        if (this.negativeCache.isNotFound(key)) {
            EcsLogger.debug("com.artipie.gradle")
                .message("Gradle artifact cached as 404 (negative cache hit)")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        final Optional<CooldownRequest> request = this.cooldownRequest(headers, path);
        if (request.isEmpty()) {
            EcsLogger.debug("com.artipie.gradle")
                .message("No cooldown check for path (doesn't match artifact pattern)")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();
            return this.fetchThroughCache(line, key, headers);
        }
        return this.cooldown.evaluate(request.get(), this.inspector)
            .thenCompose(result -> this.afterCooldown(result, line, key, headers));
    }

    private CompletableFuture<Response> afterCooldown(
        final CooldownResult result,
        final RequestLine line,
        final Key key,
        final Headers headers
    ) {
        if (result.blocked()) {
            EcsLogger.info("com.artipie.gradle")
                .message("Cooldown BLOCKED request (reason: " + result.block().orElseThrow().reason() + ", blocked until: " + result.block().orElseThrow().blockedUntil() + ")")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .eventOutcome("blocked")
                .field("package.name", key.string())
                .log();
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        EcsLogger.debug("com.artipie.gradle")
            .message("Cooldown ALLOWED request")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("package.name", key.string())
            .log();
        return this.fetchThroughCache(line, key, headers);
    }

    private CompletableFuture<Response> fetchDirect(
        final RequestLine line,
        final Key key,
        final String owner
    ) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    EcsLogger.debug("com.artipie.gradle")
                        .message("Gradle proxy upstream miss - caching 404")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .field("package.name", key.string())
                        .field("http.response.status_code", resp.status().code())
                        .log();
                    // CRITICAL: Consume body to prevent Vert.x request leak
                    return resp.body().asBytesFuture().thenApply(ignored -> {
                        // Cache 404 to avoid repeated upstream requests
                        this.negativeCache.cacheNotFound(key);
                        return ResponseBuilder.notFound().build();
                    });
                }
                this.enqueueFromHeaders(resp.headers(), key, owner);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(resp.headers())
                        .body(resp.body())
                        .build()
                );
        });
    }

    private static boolean isDirectory(final String path) {
        if (path.endsWith("/")) {
            return true;
        }
        final int slash = path.lastIndexOf('/');
        final String segment = slash >= 0 ? path.substring(slash + 1) : path;
        return !segment.contains(".");
    }

    /**
     * Check if path is a checksum file (generated as sidecar, not fetched from upstream).
     * @param path Request path
     * @return True if checksum file
     */
    private static boolean isChecksumFile(final String path) {
        return path.endsWith(".md5") || path.endsWith(".sha256")
            || path.endsWith(".asc") || path.endsWith(".sig");
    }

    /**
     * Serve checksum file from cache if present, otherwise fetch from upstream.
     * Checksums are generated as sidecars when caching artifacts, so we check cache first.
     * @param line Request line
     * @param key Checksum file key
     * @param owner Owner
     * @return Response future
     */
    private CompletableFuture<Response> serveChecksumFromStorage(
        final RequestLine line,
        final Key key,
        final String owner
    ) {
        // Try loading from cache first (checksums are stored as sidecars)
        return this.cache.load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    // Checksum exists in cache - serve it directly (fast path)
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .header("Content-Type", "text/plain")
                            .body(cached.get())
                            .build()
                    );
                } else {
                    // Checksum not in cache - try fetching from upstream
                    return this.fetchDirect(line, key, owner);
                }
            }).toCompletableFuture();
    }

    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Key key,
        final String owner,
        final CachedArtifactMetadataStore store
    ) {
        EcsLogger.debug("com.artipie.gradle")
            .message("Gradle proxy fetching upstream")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("package.name", key.string())
            .log();
        // Request deduplication: if same key is already being fetched, reuse that future
        return this.inFlight.computeIfAbsent(key, k ->
            this.client.response(line, Headers.EMPTY, Content.EMPTY)
                .thenCompose(resp -> {
                    if (!resp.status().success()) {
                        EcsLogger.warn("com.artipie.gradle")
                            .message("Gradle upstream returned error - caching 404")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .field("package.name", key.string())
                            .field("http.response.status_code", resp.status().code())
                            .log();
                        // Cache 404 to avoid repeated upstream requests
                        this.negativeCache.cacheNotFound(key);
                        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                    }
                    final DigestingContent digesting = new DigestingContent(resp.body());
                    this.enqueueFromHeaders(resp.headers(), key, owner);
                    return this.cache.load(
                        key,
                        () -> CompletableFuture.completedFuture(Optional.of(digesting.content())),
                        CacheControl.Standard.ALWAYS
                    ).thenCompose(
                        loaded -> {
                            if (loaded.isEmpty()) {
                                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                            }
                            return digesting.result()
                                .thenCompose(digests -> store.save(key, resp.headers(), digests))
                                .thenApply(headers -> ResponseBuilder.ok()
                                    .headers(headers)
                                    .body(loaded.get())
                                    .build()
                                );
                        }
                    );
                })
                .whenComplete((result, error) -> this.inFlight.remove(k))
        );
    }

    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request
    ) {
        final String owner = new Login(request).getValue();
        final String path = key.string();
        
        // Checksum files are generated as sidecars - serve from cache if present, else try upstream
        if (isChecksumFile(path) && this.storageBacked) {
            return this.serveChecksumFromStorage(line, key, owner);
        }

        // Skip caching for metadata and directories
        if (path.contains("maven-metadata.xml") || !this.storageBacked || isDirectory(path)) {
            EcsLogger.debug("com.artipie.gradle")
                .message("Gradle proxy bypassing cache")
                .eventCategory("repository")
                .eventAction("proxy_request")
                .field("url.path", path)
                .log();
            return this.fetchDirect(line, key, owner);
        }
        final CachedArtifactMetadataStore store = this.metadata.orElseThrow();
        return this.cache.load(
            key,
            Remote.EMPTY,
            CacheControl.Standard.ALWAYS
        ).thenCompose(
            cached -> {
                if (cached.isPresent()) {
                    // Fast path: serve cached content immediately with async metadata loading
                    return store.load(key).thenApply(
                        meta -> {
                            final ResponseBuilder builder = ResponseBuilder.ok().body(cached.get());
                            meta.ifPresent(metadata -> builder.headers(metadata.headers()));
                            return builder.build();
                        }
                    );
                }
                // Cache miss: fetch from upstream
                return this.fetchAndCache(line, key, owner, store);
            }
        ).toCompletableFuture();
    }

    private Optional<CooldownRequest> cooldownRequest(final Headers headers, final String path) {
        final Matcher matcher = GradleSlice.ARTIFACT.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String group = matcher.group("group");
        final String artifact = matcher.group("artifact");
        final String version = matcher.group("version");
        final String artifactName = String.format("%s.%s", group.replace('/', '.'), artifact);
        final String user = new Login(headers).getValue();
        EcsLogger.debug("com.artipie.gradle")
            .message("Gradle cooldown check")
            .eventCategory("repository")
            .eventAction("proxy_request")
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("url.path", path)
            .log();
        return Optional.of(
            new CooldownRequest(
                this.rtype,
                this.rname,
                artifactName,
                version,
                user,
                Instant.now()
            )
        );
    }

    private void enqueueFromHeaders(final Headers headers, final Key key, final String owner) {
        Long lm = null;
        try {
            lm = StreamSupport.stream(headers.spliterator(), false)
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)).toEpochMilli())
                .orElse(null);
        } catch (final DateTimeParseException ignored) {
            // ignore invalid date header
        }
        this.addEventToQueue(key, owner, Optional.ofNullable(lm));
    }

    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isPresent()) {
            // Ensure path starts with / for pattern matching
            final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();
            final Matcher matcher = GradleSlice.ARTIFACT.matcher(path);
            if (matcher.matches()) {
                final String group = matcher.group("group");
                final String artifact = matcher.group("artifact");
                final String version = matcher.group("version");
                this.events.get().add(
                    new ProxyArtifactEvent(
                        new Key.From(String.format("%s/%s/%s", group, artifact, version)),
                        this.rname,
                        owner,
                        release
                    )
                );
                EcsLogger.debug("com.artipie.gradle")
                    .message("Added Gradle proxy event")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("package.group", group)
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("user.name", owner)
                    .log();
            } else {
                EcsLogger.debug("com.artipie.gradle")
                    .message("Path did not match artifact pattern")
                    .eventCategory("repository")
                    .eventAction("proxy_request")
                    .field("url.path", path)
                    .log();
            }
        }
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

    private static final class DigestingContent {

        private final CompletableFuture<CachedArtifactMetadataStore.ComputedDigests> done;

        private final Content content;

        DigestingContent(final org.reactivestreams.Publisher<ByteBuffer> origin) {
            this.done = new CompletableFuture<>();
            this.content = new Content.From(digestingFlow(origin, this.done));
        }

        Content content() {
            return this.content;
        }

        CompletableFuture<CachedArtifactMetadataStore.ComputedDigests> result() {
            return this.done;
        }

        private static Flowable<ByteBuffer> digestingFlow(
            final org.reactivestreams.Publisher<ByteBuffer> origin,
            final CompletableFuture<CachedArtifactMetadataStore.ComputedDigests> done
        ) {
            final MessageDigest sha256 = Digests.SHA256.get();
            final MessageDigest md5 = Digests.MD5.get();
            final AtomicLong size = new AtomicLong(0L);
            return Flowable.fromPublisher(origin)
                .doOnNext(buffer -> {
                    // Update digests directly from ByteBuffer to avoid allocation
                    final ByteBuffer sha256Buf = buffer.asReadOnlyBuffer();
                    final ByteBuffer md5Buf = buffer.asReadOnlyBuffer();
                    sha256.update(sha256Buf);
                    md5.update(md5Buf);
                    size.addAndGet(buffer.remaining());
                })
                .doOnError(done::completeExceptionally)
                .doOnComplete(() -> done.complete(buildDigests(size.get(), sha256, md5)));
        }

        private static CachedArtifactMetadataStore.ComputedDigests buildDigests(
            final long size,
            final MessageDigest sha256,
            final MessageDigest md5
        ) {
            final Map<String, String> map = new HashMap<>(2);
            map.put("sha256", Hex.encodeHexString(sha256.digest()));
            map.put("md5", Hex.encodeHexString(md5.digest()));
            return new CachedArtifactMetadataStore.ComputedDigests(size, map);
        }
    }

}
