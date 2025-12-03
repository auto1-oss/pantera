/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

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
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import org.apache.commons.codec.binary.Hex;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import java.util.regex.Matcher;

/**
 * Maven proxy slice with caching, cooldown, negative cache, and metadata cache.
 * Integrates with global event queue for background processing.
 */
@SuppressWarnings({"PMD.GodClass", "PMD.ExcessiveImports"})
public final class CachedProxySlice implements Slice {

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
     * Upstream URL.
     */
    private final String upstreamUrl;

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
     * In-flight requests map for deduplication (prevents thundering herd).
     */
    private final Map<Key, CompletableFuture<Response>> inFlight = new ConcurrentHashMap<>();

    /**
     * Metadata cache for maven-metadata.xml files.
     */
    private final MetadataCache metadataCache;

    /**
     * Negative cache for 404 responses.
     */
    private final NegativeCache negativeCache;

    /**
     * Wraps origin slice with caching layer.
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param upstreamUrl Upstream URL
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     */
    @SuppressWarnings({"PMD.ConstructorOnlyInitializesOrCallOtherConstructors", "PMD.CloseResource", "PMD.ExcessiveParameterList"})
    CachedProxySlice(final Slice client, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events, final String rname, final String upstreamUrl,
        final String rtype, final CooldownService cooldown, final CooldownInspector inspector,
        final Optional<Storage> storage) {
        this(client, cache, events, rname, upstreamUrl, rtype, cooldown, inspector, storage,
            Duration.ofHours(24), Duration.ofHours(24), true);
    }

    /**
     * Wraps origin slice with caching layer with configurable cache settings.
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param upstreamUrl Upstream URL
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     * @param metadataTtl TTL for metadata cache
     * @param negativeCacheTtl TTL for negative cache
     * @param negativeCacheEnabled Whether negative caching is enabled
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    CachedProxySlice(final Slice client, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events, final String rname, final String upstreamUrl,
        final String rtype, final CooldownService cooldown, final CooldownInspector inspector,
        final Optional<Storage> storage, final Duration metadataTtl,
        final Duration negativeCacheTtl, final boolean negativeCacheEnabled) {
        this(client, cache, events, rname, upstreamUrl, rtype, cooldown, inspector, storage,
            new MavenCacheConfig(metadataTtl, 10_000, negativeCacheTtl, 50_000, negativeCacheEnabled));
    }

    /**
     * Wraps origin slice with caching layer using MavenCacheConfig.
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param upstreamUrl Upstream URL
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     * @param cacheConfig Cache configuration (TTL, maxSize, etc.)
     */
    @SuppressWarnings({"PMD.ConstructorOnlyInitializesOrCallOtherConstructors", "PMD.CloseResource", "PMD.ExcessiveParameterList"})
    CachedProxySlice(final Slice client, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events, final String rname, final String upstreamUrl,
        final String rtype, final CooldownService cooldown, final CooldownInspector inspector,
        final Optional<Storage> storage, final MavenCacheConfig cacheConfig) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.upstreamUrl = upstreamUrl;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.metadata = storage.map(CachedArtifactMetadataStore::new);
        this.storageBacked = this.metadata.isPresent() && !Objects.equals(this.cache, Cache.NOP);
        // Create caches - they auto-connect to Valkey via GlobalCacheConfig if available
        final com.artipie.cache.ValkeyConnection valkeyConn = this.initializeValkeyConnection(rname);
        
        this.metadataCache = new MetadataCache(
            cacheConfig.metadataTtl(),
            cacheConfig.metadataMaxSize(),
            valkeyConn,
            rname
        );
        this.negativeCache = new NegativeCache(
            cacheConfig.negativeTtl(),
            cacheConfig.negativeEnabled(),
            cacheConfig.negativeMaxSize(),
            valkeyConn,
            rname
        );
    }

    /**
     * Initialize Valkey connection from GlobalCacheConfig.
     * ValkeyConnection is managed by GlobalCacheConfig lifecycle, not closed here.
     * @param rname Repository name for logging
     * @return ValkeyConnection or null if unavailable
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.ConstructorOnlyInitializesOrCallOtherConstructors"})
    private com.artipie.cache.ValkeyConnection initializeValkeyConnection(final String rname) {
        final Optional<com.artipie.cache.ValkeyConnection> valkeyOpt = 
            com.artipie.cache.GlobalCacheConfig.valkeyConnection();
        final com.artipie.cache.ValkeyConnection valkeyConn = valkeyOpt.orElse(null);

        if (valkeyConn == null) {
            com.artipie.http.log.EcsLogger.warn("com.artipie.maven")
                .message("CachedProxySlice initialized WITHOUT Valkey connection - caching will be disabled (type: valkey)")
                .eventCategory("configuration")
                .eventAction("cache_init")
                .eventOutcome("failure")
                .field("repository.name", rname)
                .log();
        }
        return valkeyConn;
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

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        // Handle root path requests - don't try to cache them as they would use Key.ROOT
        if ("/".equals(path) || path.isEmpty()) {
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);
        
        // Check negative cache first (fast path for known 404s)
        if (this.negativeCache.isNotFound(key)) {
            this.recordMetric(() -> 
                com.artipie.metrics.ArtipieMetrics.instance().cacheHit("maven-negative")
            );
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        
        final Optional<CooldownRequest> request = this.cooldownRequest(headers, key);
        if (request.isEmpty()) {
            return this.fetchThroughCache(line, key, headers);
        }
        return this.cooldown.evaluate(request.get(), this.inspector)
            .thenCompose(result -> this.afterCooldown(result, line, key, headers));
    }

    private CompletableFuture<Response> afterCooldown(
        final CooldownResult result, final RequestLine line, final Key key,
        final Headers headers
    ) {
        if (result.blocked()) {
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        return this.fetchThroughCache(line, key, headers);
    }

    private CompletableFuture<Response> fetchDirect(
        final RequestLine line,
        final Key key,
        final String owner
    ) {
        final long startTime = System.currentTimeMillis();
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose((resp) -> {
                final long duration = System.currentTimeMillis() - startTime;
                // Track upstream health
                if (!resp.status().success()) {
                    if (resp.status().code() >= 500) {
                        this.trackUpstreamFailure(new RuntimeException("HTTP " + resp.status().code()));
                        this.recordProxyMetric("error", duration);
                        // Consume body to prevent Vert.x request leak
                        return resp.body().asBytesFuture()
                            .handle((bytes, err) -> ResponseBuilder.notFound().build());
                    } else {
                        this.recordMetric(() ->
                            com.artipie.metrics.ArtipieMetrics.instance().upstreamSuccess(this.rname)
                        );
                        final String result = resp.status().code() == 404 ? "not_found" : "client_error";
                        this.recordProxyMetric(result, duration);
                        // Cache 404 responses to avoid repeated upstream requests
                        // CRITICAL: Never cache checksum 404s - we generate checksums locally
                        // Caching checksum 404s breaks Maven validation when artifact is later cached
                        if (resp.status().code() == 404 && !isChecksumFile(key.string())) {
                            // CRITICAL: Consume body BEFORE caching to complete request cycle
                            return resp.body().asBytesFuture()
                                .thenApply(bytes -> {
                                    this.negativeCache.cacheNotFound(key);
                                    this.recordMetric(() ->
                                        com.artipie.metrics.ArtipieMetrics.instance().cacheMiss("maven-negative")
                                    );
                                    return ResponseBuilder.notFound().build();
                                })
                                .exceptionally(err -> ResponseBuilder.notFound().build());
                        }
                        // Other non-success responses - consume body
                        return resp.body().asBytesFuture()
                            .handle((bytes, err) -> ResponseBuilder.notFound().build());
                    }
                }
                this.recordMetric(() ->
                    com.artipie.metrics.ArtipieMetrics.instance().upstreamSuccess(this.rname)
                );
                this.recordProxyMetric("success", duration);
                this.enqueueFromHeaders(resp.headers(), key, owner);
                // Track download
                this.recordMetric(() ->
                    com.artipie.metrics.ArtipieMetrics.instance().download(this.rname, this.rtype)
                );
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(resp.headers())
                        .body(resp.body())
                        .build()
                );
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.trackUpstreamFailure(error);
                this.recordProxyMetric("exception", duration);
                this.recordUpstreamErrorMetric(error);
                throw new java.util.concurrent.CompletionException(error);
            });
    }

    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
            }
        });
    }

    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof ConnectException) {
                    errorType = "connection";
                }
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Key key,
        final String owner,
        final CachedArtifactMetadataStore store
    ) {
        // Request deduplication: if same key is already being fetched, reuse that future
        final long startTime = System.currentTimeMillis();
        return this.inFlight.computeIfAbsent(key, k ->
            this.client.response(line, Headers.EMPTY, Content.EMPTY)
                .thenCompose(resp -> this.handleUpstreamResponse(resp, key, owner, store, startTime))
                .exceptionally(error -> {
                    final long duration = System.currentTimeMillis() - startTime;
                    this.trackUpstreamFailure(error);
                    this.recordProxyMetric("exception", duration);
                    this.recordUpstreamErrorMetric(error);
                    throw new java.util.concurrent.CompletionException(error);
                })
                .whenComplete((result, error) -> this.inFlight.remove(k))
        );
    }

    private CompletableFuture<Response> handleUpstreamResponse(
        final Response resp,
        final Key key,
        final String owner,
        final CachedArtifactMetadataStore store,
        final long startTime
    ) {
        final long duration = System.currentTimeMillis() - startTime;
        if (!resp.status().success()) {
            return this.handleUpstreamError(resp, key, duration);
        }
        this.recordMetric(() ->
            com.artipie.metrics.ArtipieMetrics.instance().upstreamSuccess(this.rname)
        );
        this.recordProxyMetric("success", duration);
        final DigestingContent digesting = new DigestingContent(resp.body());
        this.enqueueFromHeaders(resp.headers(), key, owner);
        this.recordMetric(() ->
            com.artipie.metrics.ArtipieMetrics.instance().cacheMiss("maven")
        );
        this.recordMetric(() ->
            com.artipie.metrics.ArtipieMetrics.instance().download(this.rname, this.rtype)
        );
        return this.cacheAndBuildResponse(key, digesting, resp.headers(), store);
    }

    private CompletableFuture<Response> handleUpstreamError(final Response resp, final Key key, final long duration) {
        if (resp.status().code() >= 500) {
            this.trackUpstreamFailure(new RuntimeException("HTTP " + resp.status().code()));
            this.recordProxyMetric("error", duration);
        } else {
            this.recordMetric(() ->
                com.artipie.metrics.ArtipieMetrics.instance().upstreamSuccess(this.rname)
            );
            final String result = resp.status().code() == 404 ? "not_found" : "client_error";
            this.recordProxyMetric(result, duration);
        }
        return resp.body().asBytesFuture()
            .thenApply(bytes -> {
                if (resp.status().code() == 404 && !isChecksumFile(key.string())) {
                    this.negativeCache.cacheNotFound(key);
                    this.recordMetric(() ->
                        com.artipie.metrics.ArtipieMetrics.instance().cacheMiss("maven-negative")
                    );
                }
                return ResponseBuilder.notFound().build();
            })
            .exceptionally(err -> ResponseBuilder.notFound().build());
    }

    private CompletableFuture<Response> cacheAndBuildResponse(
        final Key key,
        final DigestingContent digesting,
        final Headers respHeaders,
        final CachedArtifactMetadataStore store
    ) {
        return this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(digesting.content())),
            CacheControl.Standard.ALWAYS
        ).thenCompose(loaded -> {
            if (loaded.isEmpty()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            return digesting.result()
                .thenCompose(digests -> {
                    final long size = digests.size();
                    this.recordMetric(() ->
                        com.artipie.metrics.ArtipieMetrics.instance().bandwidth(this.rname, this.rtype, "download", size)
                    );
                    return store.save(key, respHeaders, digests);
                })
                .thenApply(headers -> ResponseBuilder.ok()
                    .headers(headers)
                    .body(loaded.get())
                    .build()
                );
        }).toCompletableFuture();
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
        return path.endsWith(".md5") || path.endsWith(".sha1") || path.endsWith(".sha256")
            || path.endsWith(".sha512") || path.endsWith(".asc") || path.endsWith(".sig");
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

    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request
    ) {
        final String path = key.string();
        final String owner = new Login(request).getValue();
        
        // Checksum files are generated as sidecars - serve from storage if present, else try upstream
        if (isChecksumFile(path) && this.storageBacked) {
            return this.serveChecksumFromStorage(line, key, owner);
        }
        
        // Handle metadata with dedicated cache (major performance improvement)
        if (path.contains("maven-metadata.xml")) {
            return this.metadataCache.load(
                key,
                () -> this.fetchDirect(line, key, owner)
                    .thenApply(resp -> {
                        if (resp.status().success()) {
                            this.recordMetric(() ->
                                com.artipie.metrics.ArtipieMetrics.instance().cacheMiss("maven-metadata")
                            );
                            return Optional.of(resp.body());
                        }
                        return Optional.empty();
                    })
            ).thenApply(opt -> opt
                .map(content -> {
                    this.recordMetric(() ->
                        com.artipie.metrics.ArtipieMetrics.instance().cacheHit("maven-metadata")
                    );
                    return ResponseBuilder.ok()
                        .header("Content-Type", "text/xml")
                        .body(content)
                        .build();
                })
                .orElse(ResponseBuilder.notFound().build())
            );
        }
        
        // Skip caching for directories  
        if (!this.storageBacked || isDirectory(path)) {
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
                    // Cache hit - track metrics
                    this.recordMetric(() ->
                        com.artipie.metrics.ArtipieMetrics.instance().cacheHit("maven")
                    );
                    this.recordMetric(() ->
                        com.artipie.metrics.ArtipieMetrics.instance().download(this.rname, this.rtype)
                    );
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

    private Optional<CooldownRequest> cooldownRequest(final Headers headers, final Key key) {
        final Matcher matcher = MavenSlice.ARTIFACT.matcher(key.string());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String pkg = matcher.group("pkg");
        final int idx = pkg.lastIndexOf('/');
        if (idx < 0 || idx == pkg.length() - 1) {
            return Optional.empty();
        }
        final String version = pkg.substring(idx + 1);
        final String artifact = MavenSlice.EVENT_INFO.formatArtifactName(pkg.substring(0, idx));
        final String user = new Login(headers).getValue();
        return Optional.of(
            new CooldownRequest(
                this.rtype,
                this.rname,
                artifact,
                version,
                user,
                Instant.now()
            )
        );
    }

    /**
     * Adds artifact data to events queue, if this queue is present.
     * Note, that
     * - checksums, javadoc and sources archives are excluded
     * - event key contains package name and version, for example 'com/artipie/asto/1.5'
     * It is possible, that the same package will be added to the queue twice
     * (as one maven package can contain pom, jar, war etc. at the same time), but will not
     * be duplicated as {@link ProxyArtifactEvent} with the same package key are considered as
     * equal.
     * @param key Artifact key
     */
    private void addEventToQueue(final Key key, final String owner) {
        this.addEventToQueue(key, owner, Optional.empty());
    }

    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isPresent()) {
            final Matcher matcher = MavenSlice.ARTIFACT.matcher(key.string());
            if (matcher.matches()) {
                this.events.get().add(
                    new ProxyArtifactEvent(
                        new Key.From(matcher.group("pkg")),
                        this.rname,
                        owner,
                        release
                    )
                );
            }
        }
    }

    /**
     * Content wrapper that calculates digests while streaming data.
     */
    private static final class DigestingContent {

        /**
         * Digests promise.
         */
        private final CompletableFuture<CachedArtifactMetadataStore.ComputedDigests> done;

        /**
         * Wrapped content.
         */
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
            final MessageDigest sha1 = Digests.SHA1.get();
            final MessageDigest md5 = Digests.MD5.get();
            final AtomicLong size = new AtomicLong(0L);
            return Flowable.fromPublisher(origin)
                .doOnNext(buffer -> {
                    // Update digests directly from ByteBuffer to avoid allocation
                    final ByteBuffer sha256Buf = buffer.asReadOnlyBuffer();
                    final ByteBuffer sha1Buf = buffer.asReadOnlyBuffer();
                    final ByteBuffer md5Buf = buffer.asReadOnlyBuffer();
                    sha256.update(sha256Buf);
                    sha1.update(sha1Buf);
                    md5.update(md5Buf);
                    size.addAndGet(buffer.remaining());
                })
                .doOnError(done::completeExceptionally)
                .doOnComplete(() -> done.complete(buildDigests(size.get(), sha256, sha1, md5)));
        }

        private static CachedArtifactMetadataStore.ComputedDigests buildDigests(
            final long size,
            final MessageDigest sha256,
            final MessageDigest sha1,
            final MessageDigest md5
        ) {
            final Map<String, String> map = new HashMap<>(3);
            map.put("sha256", Hex.encodeHexString(sha256.digest()));
            map.put("sha1", Hex.encodeHexString(sha1.digest()));
            map.put("md5", Hex.encodeHexString(md5.digest()));
            return new CachedArtifactMetadataStore.ComputedDigests(size, map);
        }
    }

    /**
     * Handles root path requests without using cache to avoid Key.ROOT issues.
     * @param line Request line
     * @return Response future
     */
    private CompletableFuture<Response> handleRootPath(final RequestLine line) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (resp.status().success()) {
                    this.addEventToQueue(new KeyFromPath("/index.html"),
                        com.artipie.scheduling.ArtifactEvent.DEF_OWNER);
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .headers(resp.headers())
                            .body(resp.body())
                            .build()
                    );
                }
                // Consume body to prevent potential leak
                return resp.body().asBytesFuture()
                    .thenApply(ignored -> ResponseBuilder.notFound().build());
            });
    }

    /**
     * Track upstream failure with error classification.
     * @param error The error that occurred
     */
    private void trackUpstreamFailure(final Throwable error) {
        final String errorType;
        if (error instanceof TimeoutException) {
            errorType = "timeout";
        } else if (error instanceof ConnectException) {
            errorType = "connection_refused";
        } else if (error.getMessage() != null && error.getMessage().contains("HTTP 5")) {
            errorType = "server_error";
        } else {
            errorType = "unknown";
        }
        this.recordMetric(() ->
            com.artipie.metrics.ArtipieMetrics.instance().upstreamFailure(this.rname, this.upstreamUrl, errorType)
        );
    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
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
