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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.config.CooldownAdapterRegistry;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Abstract base class for all proxy adapter cache slices.
 *
 * <p>Implements the shared proxy flow via template method pattern:
 * <ol>
 *   <li>Check negative cache - fast-fail on known 404s</li>
 *   <li>Check local cache (offline-safe) - serve if fresh hit</li>
 *   <li>Evaluate cooldown - block if in cooldown period</li>
 *   <li>Deduplicate concurrent requests for same path</li>
 *   <li>Fetch from upstream</li>
 *   <li>On 200: cache content, compute digests, generate sidecars, enqueue event</li>
 *   <li>On 404: update negative cache</li>
 *   <li>Record metrics</li>
 * </ol>
 *
 * <p>Adapters override only the hooks they need:
 * {@link #isCacheable(String)}, {@link #buildCooldownRequest(String, Headers)},
 * {@link #digestAlgorithms()}, {@link #buildArtifactEvent(Key, Headers, long, String)},
 * {@link #postProcess(Response, RequestLine)}, {@link #generateSidecars(String, Map)}.
 *
 * @since 1.20.13
 */
@SuppressWarnings({"PMD.GodClass", "PMD.ExcessiveImports"})
public abstract class BaseCachedProxySlice implements Slice {

    /**
     * Upstream remote slice.
     */
    private final Slice client;

    /**
     * Asto cache for artifact storage.
     */
    private final Cache cache;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Repository type (e.g., "maven", "npm", "pypi").
     */
    private final String repoType;

    /**
     * Upstream base URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Optional local storage for metadata and sidecars.
     */
    private final Optional<CachedArtifactMetadataStore> metadataStore;

    /**
     * Whether cache is backed by persistent storage.
     */
    private final boolean storageBacked;

    /**
     * Event queue for proxy artifact events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Unified proxy configuration.
     */
    private final ProxyCacheConfig config;

    /**
     * Negative cache for 404 responses.
     */
    private final NegativeCache negativeCache;

    /**
     * Cooldown service (null if cooldown disabled).
     */
    private final CooldownService cooldownService;

    /**
     * Cooldown inspector (null if cooldown disabled).
     */
    private final CooldownInspector cooldownInspector;

    /**
     * Per-key request coalescer. Concurrent callers for the same cache key share
     * one cache-write loader invocation, each receiving the same
     * {@link FetchSignal} terminal state. Wired in via WI-post-05;
     * SIGNAL-strategy semantics are provided by
     * {@link SingleFlight#load(Object, Supplier)}.
     */
    private final SingleFlight<Key, FetchSignal> singleFlight;

    /**
     * Raw storage for direct saves (bypasses FromStorageCache lazy tee-content).
     */
    private final Optional<Storage> storage;

    /**
     * Optional post-write callback (Phase-4 / Task-19a extension point). Fires
     * once per successful primary cache-write; callback exceptions are caught +
     * logged and never propagate to the cache-write path. Defaults to a no-op.
     *
     * <p>This mirrors {@link ProxyCacheWriter#fireOnWrite} for the actual
     * production cache-write path used by every proxy adapter.
     */
    private final Consumer<CacheWriteEvent> onCacheWrite;

    /** No-op {@link CacheWriteEvent} consumer used when no callback is supplied. */
    private static final Consumer<CacheWriteEvent> NO_OP_ON_CACHE_WRITE = event -> { };

    /**
     * Constructor with cooldown + post-write callback.
     *
     * @param client Upstream remote slice
     * @param cache Asto cache for artifact storage
     * @param repoName Repository name
     * @param repoType Repository type
     * @param upstreamUrl Upstream base URL
     * @param storage Optional local storage
     * @param events Event queue for proxy artifacts
     * @param config Unified proxy configuration
     * @param cooldownService Cooldown service (nullable, required if cooldown enabled)
     * @param cooldownInspector Cooldown inspector (nullable, required if cooldown enabled)
     * @param onCacheWrite Optional post-write callback. May be {@code null}
     *     (treated as no-op). Throwables propagated from the callback are
     *     caught + logged and do NOT affect the cache-write outcome.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    protected BaseCachedProxySlice(
        final Slice client,
        final Cache cache,
        final String repoName,
        final String repoType,
        final String upstreamUrl,
        final Optional<Storage> storage,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final ProxyCacheConfig config,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector,
        final Consumer<CacheWriteEvent> onCacheWrite
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
        this.repoType = Objects.requireNonNull(repoType, "repoType");
        this.upstreamUrl = Objects.requireNonNull(upstreamUrl, "upstreamUrl");
        this.events = Objects.requireNonNull(events, "events");
        this.config = Objects.requireNonNull(config, "config");
        this.storage = storage;
        this.metadataStore = storage.map(CachedArtifactMetadataStore::new);
        this.storageBacked = this.metadataStore.isPresent()
            && !Objects.equals(this.cache, Cache.NOP);
        if (!config.negativeCacheEnabled()) {
            this.negativeCache = null;
        } else if (NegativeCacheRegistry.instance().isSharedCacheSet()) {
            this.negativeCache = NegativeCacheRegistry.instance().sharedCache();
        } else {
            // No shared bean wired (tests, early startup) — give this slice
            // its own private cache so other slices' state cannot leak in.
            this.negativeCache = new NegativeCache(
                new com.auto1.pantera.cache.NegativeCacheConfig()
            );
        }
        this.cooldownService = cooldownService;
        this.cooldownInspector = cooldownInspector;
        this.onCacheWrite = onCacheWrite == null ? NO_OP_ON_CACHE_WRITE : onCacheWrite;
        // Zombie TTL honours PANTERA_DEDUP_MAX_AGE_MS (default 5 min). 10K max
        // in-flight entries bounds memory. Completion hops via
        // ForkJoinPool.commonPool() — the same executor pattern used by the
        // other WI-05 sites (CachedNpmProxySlice migration).
        this.singleFlight = new SingleFlight<>(
            Duration.ofMillis(
                ConfigDefaults.getLong("PANTERA_DEDUP_MAX_AGE_MS", 300_000L)
            ),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
    }

    /**
     * Constructor with cooldown; no post-write callback (no-op default).
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    protected BaseCachedProxySlice(
        final Slice client,
        final Cache cache,
        final String repoName,
        final String repoType,
        final String upstreamUrl,
        final Optional<Storage> storage,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final ProxyCacheConfig config,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector
    ) {
        this(client, cache, repoName, repoType, upstreamUrl,
            storage, events, config, cooldownService, cooldownInspector,
            NO_OP_ON_CACHE_WRITE);
    }

    /**
     * Convenience constructor without cooldown or post-write callback (for
     * adapters that don't use either).
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    protected BaseCachedProxySlice(
        final Slice client,
        final Cache cache,
        final String repoName,
        final String repoType,
        final String upstreamUrl,
        final Optional<Storage> storage,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final ProxyCacheConfig config
    ) {
        this(client, cache, repoName, repoType, upstreamUrl,
            storage, events, config, null, null, NO_OP_ON_CACHE_WRITE);
    }

    @Override
    public final CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String path = line.uri().getPath();
        if ("/".equals(path) || path.isEmpty()) {
            return this.handleRootPath(line, headers);
        }
        final Key key = new KeyFromPath(path);
        // Step 1: Negative cache fast-fail
        if (this.negativeCache != null
            && this.negativeCache.isKnown404(this.negKey(path))) {
            this.logDebug("Negative cache hit", path);
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        // Step 2: Pre-process hook (adapter-specific short-circuit)
        final Optional<CompletableFuture<Response>> pre =
            this.preProcess(line, headers, key, path);
        if (pre.isPresent()) {
            return pre.get();
        }
        // Step 3: Check if path is cacheable at all
        if (!this.isCacheable(path)) {
            return this.fetchDirect(line, key, headers, new Login(headers).getValue());
        }
        // Step 4: Cache-first (offline-safe) — check cache before any network calls
        if (this.storageBacked) {
            return this.cacheFirstFlow(line, headers, key, path);
        }
        // No persistent storage — go directly to upstream
        return this.fetchDirect(line, key, headers, new Login(headers).getValue());
    }

    /**
     * Build the header set that should accompany an upstream proxy request.
     * Forwards the client's {@code User-Agent} and {@code Accept} so the
     * remote registry sees a native ecosystem tool (e.g. {@code npm/...},
     * {@code Go-http-client/1.1}) rather than Pantera. When the inbound
     * request omitted {@code User-Agent} entirely, falls back to a realistic
     * default for this adapter's repo type — so an unidentified client
     * still doesn't make us look like a bot to the upstream.
     *
     * <p>Everything else is dropped (Authorization, Cookie, Host,
     * X-Forwarded-*, etc.) — those are local-to-Pantera and would either
     * leak credentials or confuse the upstream router.
     *
     * @param incoming Headers from the client request to Pantera (may be empty)
     * @return Headers safe to forward to the upstream proxy target
     */
    private Headers upstreamHeaders(final Headers incoming) {
        final Headers out = new Headers();
        final java.util.List<com.auto1.pantera.http.headers.Header> ua =
            incoming.find("User-Agent");
        if (ua.isEmpty()) {
            out.add("User-Agent",
                com.auto1.pantera.http.EcosystemUserAgents.defaultFor(this.repoType));
        } else {
            out.add(ua.get(0), true);
        }
        final java.util.List<com.auto1.pantera.http.headers.Header> accept =
            incoming.find("Accept");
        if (!accept.isEmpty()) {
            out.add(accept.get(0), true);
        }
        return out;
    }

    // ===== Abstract hooks — adapters override these =====

    /**
     * Determine if a request path is cacheable.
     * @param path Request path (e.g., "/com/example/foo/1.0/foo-1.0.jar")
     * @return True if this path should be cached
     */
    protected abstract boolean isCacheable(String path);

    // ===== Overridable hooks with defaults =====

    /**
     * Build a cooldown request from the path.
     * Return empty to skip cooldown for this path.
     * @param path Request path
     * @param headers Request headers
     * @return Cooldown request or empty
     */
    protected Optional<CooldownRequest> buildCooldownRequest(
        final String path, final Headers headers
    ) {
        return Optional.empty();
    }

    /**
     * Return the set of digest algorithms to compute during cache streaming.
     * Return empty set to skip digest computation.
     * Override in adapters to enable digest computation (e.g., SHA-256, MD5).
     * @return Set of algorithm names (e.g., "SHA-256", "MD5")
     */
    protected java.util.Set<String> digestAlgorithms() {
        return Collections.emptySet();
    }

    /**
     * Build a proxy artifact event for the event queue.
     * Return empty to skip event emission.
     * @param key Artifact cache key
     * @param responseHeaders Upstream response headers
     * @param size Artifact size in bytes
     * @param owner Authenticated user login
     * @return Proxy artifact event or empty
     */
    protected Optional<ProxyArtifactEvent> buildArtifactEvent(
        final Key key, final Headers responseHeaders, final long size,
        final String owner
    ) {
        return Optional.empty();
    }

    /**
     * Post-process response before returning to caller.
     * Default: identity (no transformation).
     * @param response The response to post-process
     * @param line Original request line
     * @return Post-processed response
     */
    protected Response postProcess(final Response response, final RequestLine line) {
        return response;
    }

    /**
     * Generate sidecar files from computed digests.
     * Default: empty list (no sidecars).
     * @param path Original artifact path
     * @param digests Computed digests map (algorithm -> hex value)
     * @return List of sidecar files to store alongside the artifact
     */
    protected List<SidecarFile> generateSidecars(
        final String path, final Map<String, String> digests
    ) {
        return Collections.emptyList();
    }

    /**
     * Check if path is a sidecar checksum file that should be served from cache.
     * Default: false. Override in adapters that generate checksum sidecars.
     * @param path Request path
     * @return True if this is a checksum sidecar file
     */
    protected boolean isChecksumSidecar(final String path) {
        return false;
    }

    /**
     * Pre-process a request before the standard flow.
     * If non-empty, the returned response short-circuits the standard flow.
     * Use for adapter-specific handling (e.g., Maven metadata cache).
     * Default: empty (use standard flow for all paths).
     * @param line Request line
     * @param headers Request headers
     * @param key Cache key
     * @param path Request path
     * @return Optional future response to short-circuit, or empty for standard flow
     */
    protected Optional<CompletableFuture<Response>> preProcess(
        final RequestLine line, final Headers headers, final Key key, final String path
    ) {
        return Optional.empty();
    }

    // ===== Protected accessors for subclass use =====

    /**
     * @return Repository name
     */
    protected final String repoName() {
        return this.repoName;
    }

    /**
     * @return Repository type
     */
    protected final String repoType() {
        return this.repoType;
    }

    /**
     * @return Upstream URL
     */
    protected final String upstreamUrl() {
        return this.upstreamUrl;
    }

    /**
     * @return The upstream client slice
     */
    protected final Slice client() {
        return this.client;
    }

    /**
     * @return The asto cache
     */
    protected final Cache cache() {
        return this.cache;
    }

    /**
     * @return Proxy cache config
     */
    protected final ProxyCacheConfig config() {
        return this.config;
    }

    /**
     * @return Metadata store if storage-backed
     */
    protected final Optional<CachedArtifactMetadataStore> metadataStore() {
        return this.metadataStore;
    }

    // ===== Internal flow implementation =====

    /**
     * Cache-first flow: check cache, then evaluate cooldown, then fetch.
     */
    private CompletableFuture<Response> cacheFirstFlow(
        final RequestLine line,
        final Headers headers,
        final Key key,
        final String path
    ) {
        // Checksum sidecars: serve from storage if present, else try upstream
        if (this.isChecksumSidecar(path)) {
            return this.serveChecksumFromStorage(line, key, headers, new Login(headers).getValue());
        }
        final CachedArtifactMetadataStore store = this.metadataStore.orElseThrow();
        return this.cache.load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    this.logDebug("Cache hit", path);
                    // Fast path: serve from cache with async metadata
                    return store.load(key).thenApply(meta -> {
                        final ResponseBuilder builder = ResponseBuilder.ok()
                            .body(cached.get());
                        meta.ifPresent(m -> builder.headers(stripContentEncoding(m.headers())));
                        return this.postProcess(builder.build(), line);
                    });
                }
                // Cache miss: evaluate cooldown then fetch
                return this.evaluateCooldownAndFetch(line, headers, key, path, store);
            }).toCompletableFuture();
    }

    /**
     * Evaluate cooldown, then fetch from upstream if allowed.
     */
    private CompletableFuture<Response> evaluateCooldownAndFetch(
        final RequestLine line,
        final Headers headers,
        final Key key,
        final String path,
        final CachedArtifactMetadataStore store
    ) {
        if (this.config.cooldownEnabled()
            && this.cooldownService != null
            && this.cooldownInspector != null) {
            final Optional<CooldownRequest> request =
                this.buildCooldownRequest(path, headers);
            if (request.isPresent()) {
                return this.cooldownService.evaluate(request.get(), this.cooldownInspector)
                    .thenCompose(result -> {
                        if (result.blocked()) {
                            final CooldownBlock block = result.block().orElseThrow();
                            return CompletableFuture.completedFuture(
                                buildForbiddenResponse(block, this.repoType)
                            );
                        }
                        return this.fetchAndCache(line, key, headers, store);
                    });
            }
        }
        return this.fetchAndCache(line, key, headers, store);
    }

    /**
     * Gate the supplied async action with a cooldown evaluation.
     *
     * <p>When cooldown is enabled and a {@link CooldownRequest} can be built
     * for the path, the request is evaluated first. If blocked, a 403
     * response is returned immediately and {@code onAllow} is never called.
     * If allowed (or if cooldown is disabled / not applicable), {@code onAllow}
     * is invoked and its result returned.</p>
     *
     * <p>Use this in subclass {@link #preProcess} overrides to gate
     * adapter-specific short-circuit paths that bypass
     * {@link #evaluateCooldownAndFetch} (e.g., the Maven ProxyCacheWriter
     * path introduced by WI-07).</p>
     *
     * @param headers Request headers (used by {@link #buildCooldownRequest})
     * @param path    Request path (used by {@link #buildCooldownRequest})
     * @param onAllow Supplier of the downstream action to run if allowed
     * @return Future that resolves to either a 403 block response or the
     *     result of {@code onAllow}
     */
    protected final CompletableFuture<Response> evaluateCooldownOrProceed(
        final Headers headers,
        final String path,
        final Supplier<CompletableFuture<Response>> onAllow
    ) {
        if (this.config.cooldownEnabled()
            && this.cooldownService != null
            && this.cooldownInspector != null) {
            final Optional<CooldownRequest> request =
                this.buildCooldownRequest(path, headers);
            if (request.isPresent()) {
                return this.cooldownService.evaluate(request.get(), this.cooldownInspector)
                    .thenCompose(result -> {
                        if (result.blocked()) {
                            return CompletableFuture.completedFuture(
                                buildForbiddenResponse(result.block().orElseThrow(), this.repoType)
                            );
                        }
                        return onAllow.get();
                    })
                    // Fail-open on evaluate errors (inspector timeout, upstream
                    // HEAD failure, parse error, etc). Availability > strictness:
                    // a broken cooldown evaluator must NOT block legitimate
                    // artifact serving. Matches the MetadataFilterService
                    // pass-through-on-error behavior.
                    .exceptionallyCompose(err -> {
                        EcsLogger.warn("com.auto1.pantera.cooldown")
                            .message("Cooldown evaluate failed; proceeding without block")
                            .eventCategory("database")
                            .eventAction("cooldown_evaluate_failure")
                            .eventOutcome("failure")
                            .field("repository.type", this.repoType)
                            .field("repository.name", this.repoName)
                            .field("url.path", path)
                            .error(err)
                            .log();
                        return onAllow.get();
                    });
            }
        }
        return onAllow.get();
    }

    /**
     * Build a 403 Forbidden response for a cooldown block.
     * Uses the per-adapter {@link CooldownResponseFactory} from the
     * {@link CooldownAdapterRegistry} when a bundle is registered for the
     * repo type; otherwise falls back to the {@link CooldownResponseRegistry}
     * factory for the same repo type. Factory registration is mandatory —
     * if neither registry has an entry for {@code repoType}, this method
     * throws {@link IllegalStateException} (fail-fast; no silent defaults).
     *
     * @param block Block details
     * @param repoType Repository type for factory lookup
     * @return HTTP 403 response
     * @throws IllegalStateException if no factory is registered for the
     *     given repo type in either registry
     */
    private static Response buildForbiddenResponse(
        final CooldownBlock block,
        final String repoType
    ) {
        return CooldownAdapterRegistry.instance().get(repoType)
            .map(bundle -> bundle.responseFactory().forbidden(block))
            .orElseGet(() -> CooldownResponseRegistry.instance().getOrThrow(repoType).forbidden(block));
    }

    /**
     * Fetch from upstream and cache the result, with request deduplication.
     * Uses NIO temp file streaming to avoid buffering full artifacts on heap.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Key key,
        final Headers headers,
        final CachedArtifactMetadataStore store
    ) {
        final String owner = new Login(headers).getValue();
        final long startTime = System.currentTimeMillis();
        return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)
            .thenCompose(resp -> {
                final long duration = System.currentTimeMillis() - startTime;
                if (resp.status().code() == 404) {
                    return this.handle404(resp, key, duration)
                        .thenCompose(signal ->
                            this.signalToResponse(signal, line, key, headers, store));
                }
                if (!resp.status().success()) {
                    return this.handleNonSuccess(resp, key, duration)
                        .thenCompose(signal ->
                            this.signalToResponse(signal, line, key, headers, store));
                }
                this.recordProxyMetric("success", duration);
                return this.singleFlight.load(key, () -> {
                    return this.cacheResponse(resp, key, owner, store)
                        .thenApply(r -> FetchSignal.SUCCESS);
                }).thenCompose(signal ->
                    this.signalToResponse(signal, line, key, headers, store));
            })
            .handle((resp, error) -> {
                if (error != null) {
                    final long duration = System.currentTimeMillis() - startTime;
                    this.trackUpstreamFailure(error);
                    this.recordProxyMetric("exception", duration);
                    EcsLogger.warn("com.auto1.pantera." + this.repoType)
                        .message("Upstream request failed with exception")
                        .eventCategory("web")
                        .eventAction("proxy_upstream")
                        .eventOutcome("failure")
                        .field("repository.name", this.repoName)
                        .field("event.duration", duration)
                        .error(error)
                        .log();
                    return this.tryServeStale(
                        key,
                        () -> CompletableFuture.completedFuture(
                            ResponseBuilder.unavailable()
                                .textBody("Upstream temporarily unavailable")
                                .build()
                        )
                    );
                }
                return CompletableFuture.completedFuture(resp);
            })
            .thenCompose(future -> future);
    }

    /**
     * Convert a dedup signal into an HTTP response.
     */
    private CompletableFuture<Response> signalToResponse(
        final FetchSignal signal,
        final RequestLine line,
        final Key key,
        final Headers headers,
        final CachedArtifactMetadataStore store
    ) {
        switch (signal) {
            case SUCCESS:
                // Read from cache (populated by the winning fetch)
                return this.cache.load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
                    .thenCompose(cached -> {
                        if (cached.isPresent()) {
                            return store.load(key).thenApply(meta -> {
                                final ResponseBuilder builder = ResponseBuilder.ok()
                                    .body(cached.get());
                                meta.ifPresent(m -> builder.headers(stripContentEncoding(m.headers())));
                                return this.postProcess(builder.build(), line);
                            });
                        }
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }).toCompletableFuture();
            case NOT_FOUND:
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            case ERROR:
            default:
                // Coalescer fetch failed and stale cache unavailable → 503.
                // Previously this path emitted 503 silently (zero app-layer
                // log, only the access log). Pair the response with a
                // structured WARN so operators can distinguish this failure
                // mode from other 503 sources (circuit-breaker fast-fail,
                // RepoBulkhead overload, raw upstream passthrough).
                EcsLogger.warn("com.auto1.pantera." + this.repoType)
                    .message("SingleFlight coalescer returned ERROR — "
                        + "serving stale if available, else 503")
                    .eventCategory("web")
                    .eventAction("proxy_fetch_coalesced_error")
                    .eventOutcome("failure")
                    .field("event.reason", "coalescer_error")
                    .field("repository.name", this.repoName)
                    .field("url.path", line.uri().getPath())
                    .log();
                return this.tryServeStale(
                    key,
                    () -> CompletableFuture.completedFuture(
                        ResponseBuilder.unavailable()
                            .textBody("Upstream temporarily unavailable")
                            .build()
                    )
                );
        }
    }

    /**
     * Cache a successful upstream response using NIO temp file streaming.
     * Streams body to a temp file while computing digests incrementally,
     * then saves from temp file to cache. Never buffers the full artifact on heap.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<FetchSignal> cacheResponse(
        final Response resp,
        final Key key,
        final String owner,
        final CachedArtifactMetadataStore store
    ) {
        final Path tempFile;
        final FileChannel channel;
        try {
            tempFile = Files.createTempFile("pantera-cache-", ".tmp");
            tempFile.toFile().deleteOnExit();
            channel = FileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (final IOException ex) {
            EcsLogger.warn("com.auto1.pantera." + this.repoType)
                .message("Failed to create temp file for cache streaming")
                .eventCategory("web")
                .eventAction("proxy_cache")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("file.path", key.string())
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                FetchSignal.ERROR
            );
        }
        final Map<String, MessageDigest> digests =
            DigestComputer.createDigests(this.digestAlgorithms());
        final AtomicLong totalSize = new AtomicLong(0);
        final CompletableFuture<Void> streamDone = new CompletableFuture<>();
        resp.body().subscribe(new org.reactivestreams.Subscriber<>() {
            private org.reactivestreams.Subscription sub;

            @Override
            public void onSubscribe(final org.reactivestreams.Subscription subscription) {
                this.sub = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final ByteBuffer buf) {
                try {
                    final int nbytes = buf.remaining();
                    DigestComputer.updateDigests(digests, buf);
                    final ByteBuffer copy = buf.asReadOnlyBuffer();
                    while (copy.hasRemaining()) {
                        channel.write(copy);
                    }
                    totalSize.addAndGet(nbytes);
                } catch (final IOException ex) {
                    this.sub.cancel();
                    streamDone.completeExceptionally(ex);
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                closeChannelQuietly(channel);
                deleteTempQuietly(tempFile);
                streamDone.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                try {
                    channel.force(true);
                    channel.close();
                    streamDone.complete(null);
                } catch (final IOException ex) {
                    closeChannelQuietly(channel);
                    streamDone.completeExceptionally(ex);
                }
            }
        });
        return streamDone.thenCompose(v -> {
            final Map<String, String> digestResults =
                DigestComputer.finalizeDigests(digests);
            final long size = totalSize.get();
            return this.saveFromTempFile(key, tempFile, size)
                .thenCompose(loaded -> {
                    final Map<String, String> digestsCopy =
                        new java.util.HashMap<>(digestResults);
                    final CachedArtifactMetadataStore.ComputedDigests computed =
                        new CachedArtifactMetadataStore.ComputedDigests(
                            size, digestsCopy
                        );
                    return store.save(key, stripContentEncoding(resp.headers()), computed);
                }).thenCompose(savedHeaders -> {
                    final List<SidecarFile> sidecars =
                        this.generateSidecars(key.string(), digestResults);
                    if (sidecars.isEmpty()) {
                        return CompletableFuture.completedFuture(
                            (Void) null
                        );
                    }
                    final CompletableFuture<?>[] writes;
                    if (this.storage.isPresent()) {
                        // Save sidecars directly to storage (avoids lazy tee-content)
                        writes = sidecars.stream()
                            .map(sc -> this.storage.get().save(
                                new Key.From(sc.path()),
                                new Content.From(sc.content())
                            ))
                            .toArray(CompletableFuture[]::new);
                    } else {
                        writes = sidecars.stream()
                            .map(sc -> this.cache.load(
                                new Key.From(sc.path()),
                                () -> CompletableFuture.completedFuture(
                                    Optional.of(new Content.From(sc.content()))
                                ),
                                CacheControl.Standard.ALWAYS
                            ))
                            .toArray(CompletableFuture[]::new);
                    }
                    return CompletableFuture.allOf(writes);
                }).thenApply(ignored -> {
                    this.enqueueEvent(key, resp.headers(), size, owner);
                    // Fire onCacheWrite BEFORE deleting the temp file: the
                    // CacheWriteEvent.bytesOnDisk() must still be a valid
                    // path at the moment the consumer receives it. Mirrors
                    // ProxyCacheWriter.commit() ordering (Task 11).
                    this.fireOnCacheWrite(key, tempFile, size);
                    deleteTempQuietly(tempFile);
                    return FetchSignal.SUCCESS;
                });
        }).exceptionally(err -> {
            deleteTempQuietly(tempFile);
            EcsLogger.warn("com.auto1.pantera." + this.repoType)
                .message("Failed to cache upstream response")
                .eventCategory("web")
                .eventAction("proxy_cache")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("file.path", key.string())
                .error(err)
                .log();
            return FetchSignal.ERROR;
        });
    }

    /**
     * Save content to cache from a temp file using NIO streaming.
     * Saves directly to storage to avoid FromStorageCache's lazy tee-content
     * which requires the returned Content to be consumed for the save to happen.
     * @param key Cache key
     * @param tempFile Temp file with content
     * @param size File size in bytes
     * @return Save future
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<?> saveFromTempFile(
        final Key key, final Path tempFile, final long size
    ) {
        if (this.storage.isPresent()) {
            final Content content = new Content.From(
                Optional.of(size), filePublisher(tempFile)
            );
            return this.storage.get().save(key, content);
        }
        // Fallback: use cache.load (non-storage-backed mode)
        final Content content = new Content.From(
            Optional.of(size), filePublisher(tempFile)
        );
        return this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(content)),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture();
    }

    /**
     * Create a reactive-streams {@link org.reactivestreams.Publisher} that reads
     * a temp file in 64 KB chunks. Replaces the previous {@code Flowable.using}
     * pattern so this class no longer imports {@code io.reactivex.Flowable}.
     *
     * @param tempFile Temp file to read
     * @return Publisher of ByteBuffer chunks
     */
    private static org.reactivestreams.Publisher<ByteBuffer> filePublisher(final Path tempFile) {
        return subscriber -> {
            final FileChannel[] holder = new FileChannel[1];
            try {
                holder[0] = FileChannel.open(tempFile, StandardOpenOption.READ);
            } catch (final IOException ex) {
                subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                    @Override public void request(final long n) { }
                    @Override public void cancel() { }
                });
                subscriber.onError(ex);
                return;
            }
            final FileChannel chan = holder[0];
            subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                private volatile boolean cancelled;

                @Override
                @SuppressWarnings("PMD.AvoidCatchingGenericException")
                public void request(final long n) {
                    try {
                        long remaining = n;
                        while (remaining > 0 && !this.cancelled) {
                            final ByteBuffer buf = ByteBuffer.allocate(65_536);
                            final int read = chan.read(buf);
                            if (read < 0) {
                                chan.close();
                                subscriber.onComplete();
                                return;
                            }
                            buf.flip();
                            subscriber.onNext(buf);
                            remaining--;
                        }
                    } catch (final Exception ex) {
                        closeChannelQuietly(chan);
                        subscriber.onError(ex);
                    }
                }

                @Override
                public void cancel() {
                    this.cancelled = true;
                    closeChannelQuietly(chan);
                }
            });
        };
    }

    /**
     * Close a FileChannel quietly.
     * @param channel Channel to close
     */
    private static void closeChannelQuietly(final FileChannel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to close file channel")
                .error(ex)
                .log();
        }
    }

    /**
     * Delete a temp file quietly.
     * @param path Temp file to delete
     */
    private static void deleteTempQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to delete temp file")
                .error(ex)
                .log();
        }
    }

    /**
     * Fetch directly from upstream without caching (non-cacheable paths).
     */
    private CompletableFuture<Response> fetchDirect(
        final RequestLine line, final Key key,
        final Headers incomingHeaders, final String owner
    ) {
        final long startTime = System.currentTimeMillis();
        return this.client.response(line, this.upstreamHeaders(incomingHeaders), Content.EMPTY)
            .thenCompose(resp -> {
                final long duration = System.currentTimeMillis() - startTime;
                if (!resp.status().success()) {
                    if (resp.status().code() == 404) {
                        if (this.negativeCache != null
                            && !this.isChecksumSidecar(key.string())) {
                            final NegativeCacheKey nk = this.negKey(line.uri().getPath());
                            resp.body().asBytesFuture().thenAccept(
                                bytes -> this.negativeCache.cacheNotFound(nk)
                            );
                        }
                        this.recordProxyMetric("not_found", duration);
                    } else if (resp.status().code() >= 500) {
                        this.trackUpstreamFailure(
                            new RuntimeException("HTTP " + resp.status().code())
                        );
                        this.recordProxyMetric("error", duration);
                    } else {
                        this.recordProxyMetric("client_error", duration);
                    }
                    return resp.body().asBytesFuture()
                        .thenApply(bytes -> ResponseBuilder.notFound().build());
                }
                this.recordProxyMetric("success", duration);
                this.enqueueEvent(key, resp.headers(), -1, owner);
                return CompletableFuture.completedFuture(
                    this.postProcess(
                        ResponseBuilder.ok()
                            .headers(stripContentEncoding(resp.headers()))
                            .body(resp.body())
                            .build(),
                        line
                    )
                );
            })
            .exceptionally(error -> {
                final long duration = System.currentTimeMillis() - startTime;
                this.trackUpstreamFailure(error);
                this.recordProxyMetric("exception", duration);
                EcsLogger.warn("com.auto1.pantera." + this.repoType)
                    .message("Direct upstream request failed with exception")
                    .eventCategory("web")
                    .eventAction("proxy_upstream")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("event.duration", duration)
                    .error(error)
                    .log();
                return ResponseBuilder.unavailable()
                    .textBody("Upstream error")
                    .build();
            });
    }

    private CompletableFuture<FetchSignal> handle404(
        final Response resp, final Key key, final long duration
    ) {
        this.recordProxyMetric("not_found", duration);
        return resp.body().asBytesFuture().thenApply(bytes -> {
            if (this.negativeCache != null && !this.isChecksumSidecar(key.string())) {
                this.negativeCache.cacheNotFound(this.negKey(key.string()));
            }
            return FetchSignal.NOT_FOUND;
        });
    }

    /**
     * Build a structured negative-cache key for a request path. Stores the
     * path as artifactName with empty version — sufficient for uniqueness
     * and gives operators meaningful scope/repoType columns in the admin UI.
     */
    private NegativeCacheKey negKey(final String path) {
        return NegativeCacheKey.fromPath(this.repoName, this.repoType, path);
    }

    private CompletableFuture<FetchSignal> handleNonSuccess(
        final Response resp, final Key key, final long duration
    ) {
        if (resp.status().code() >= 500) {
            this.trackUpstreamFailure(
                new RuntimeException("HTTP " + resp.status().code())
            );
            this.recordProxyMetric("error", duration);
        } else {
            this.recordProxyMetric("client_error", duration);
        }
        return resp.body().asBytesFuture()
            .thenApply(bytes -> resp.status().code() < 500
                ? FetchSignal.NOT_FOUND
                : FetchSignal.ERROR);
    }

    /**
     * Try to serve stale cached bytes when upstream has failed.
     *
     * <p>If {@link ProxyCacheConfig#staleWhileRevalidateEnabled()} is true AND
     * the backing storage contains bytes for {@code key}, returns a 200 response
     * from storage with the {@code X-Pantera-Stale: true} header set.
     *
     * <p>Note on {@code staleMaxAge}: the backing {@link Storage} API (as implemented
     * by {@code InMemoryStorage} and {@code FileStorage}) does not guarantee that
     * {@code Meta.OP_UPDATED_AT} is populated. Age-based expiry is therefore not enforced
     * here; operators should rely on cache-layer TTL or eviction policies instead.
     *
     * @param key Cache key for the artifact
     * @param fallback Supplier of the original error response (used when stale is unavailable)
     * @return Future response — either stale 200 or the original error
     */
    private CompletableFuture<Response> tryServeStale(
        final Key key,
        final Supplier<CompletableFuture<Response>> fallback
    ) {
        if (!this.config.staleWhileRevalidateEnabled() || this.storage.isEmpty()) {
            return fallback.get();
        }
        if (this.metadataStore.isPresent()) {
            return this.metadataStore.get().load(key).thenCompose(metaOpt -> {
                if (metaOpt.isEmpty()) {
                    return this.serveStaleFromStorage(key, fallback);
                }
                final CachedArtifactMetadataStore.Metadata meta = metaOpt.get();
                final Duration age = Duration.between(meta.savedAt(), Instant.now());
                if (age.compareTo(this.config.staleMaxAge()) > 0) {
                    EcsLogger.warn("com.auto1.pantera." + this.repoType)
                        .message("Stale artifact too old, refusing to serve"
                            + " (age_seconds=" + age.getSeconds()
                            + ", max_age_seconds=" + this.config.staleMaxAge().getSeconds() + ")")
                        .eventCategory("network")
                        .eventAction("stale_too_old")
                        .eventOutcome("failure")
                        .field("repository.name", this.repoName)
                        .field("url.path", key.string())
                        .log();
                    return fallback.get();
                }
                return this.serveStaleFromStorageWithAge(key, fallback, age);
            });
        }
        return this.serveStaleFromStorage(key, fallback);
    }

    private CompletableFuture<Response> serveStaleFromStorage(
        final Key key,
        final Supplier<CompletableFuture<Response>> fallback
    ) {
        final Storage store = this.storage.get();
        return store.exists(key).thenCompose(exists -> {
            if (!exists) {
                return fallback.get();
            }
            return serveStaleFromStorageWithAge(key, fallback, null);
        });
    }

    private CompletableFuture<Response> serveStaleFromStorageWithAge(
        final Key key,
        final Supplier<CompletableFuture<Response>> fallback,
        final Duration age
    ) {
        final Storage store = this.storage.get();
        return store.value(key)
            .thenApply(content -> {
                EcsLogger.warn("com.auto1.pantera." + this.repoType)
                    .message("Upstream failed, serving stale cached artifact")
                    .eventCategory("network")
                    .eventAction("stale_serve")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("url.path", key.string())
                    .log();
                final ResponseBuilder builder = ResponseBuilder.ok()
                    .header("X-Pantera-Stale", "true");
                if (age != null) {
                    builder.header("Age", String.valueOf(age.getSeconds()));
                }
                return (Response) builder.body(content).build();
            })
            .exceptionallyCompose(err -> {
                EcsLogger.warn("com.auto1.pantera." + this.repoType)
                    .message("Failed to read stale artifact from storage")
                    .eventCategory("web")
                    .eventAction("stale_serve")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", key.string())
                    .error(err)
                    .log();
                return fallback.get();
            });
    }

    private CompletableFuture<Response> serveChecksumFromStorage(
        final RequestLine line, final Key key,
        final Headers incomingHeaders, final String owner
    ) {
        return this.cache.load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .header("Content-Type", "text/plain")
                            .body(cached.get())
                            .build()
                    );
                }
                return this.fetchDirect(line, key, incomingHeaders, owner);
            }).toCompletableFuture();
    }

    private CompletableFuture<Response> handleRootPath(
        final RequestLine line, final Headers headers
    ) {
        return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)
            .thenCompose(resp -> {
                if (resp.status().success()) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .headers(stripContentEncoding(resp.headers()))
                            .body(resp.body())
                            .build()
                    );
                }
                return resp.body().asBytesFuture()
                    .thenApply(ignored -> ResponseBuilder.notFound().build());
            });
    }

    /**
     * Invoke the configured {@link Consumer} with a fresh
     * {@link CacheWriteEvent}. Any throwable from the consumer is caught
     * and logged at WARN — it MUST NOT propagate, otherwise the cache-
     * write outcome would be tied to the consumer's correctness. Mirrors
     * the contract pinned by {@link ProxyCacheWriter#fireOnWrite}.
     *
     * @param key      Cache key of the primary artifact just written.
     * @param tempFile Filesystem path of the source bytes (alive at fire
     *                 time; deleted by the caller immediately after this
     *                 method returns).
     * @param size     Size in bytes of the primary.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void fireOnCacheWrite(final Key key, final Path tempFile, final long size) {
        try {
            this.onCacheWrite.accept(new CacheWriteEvent(
                this.repoName, key.string(), tempFile, size, Instant.now()
            ));
        } catch (final Exception thrown) {
            EcsLogger.warn("com.auto1.pantera.http.cache")
                .message("BaseCachedProxySlice onCacheWrite callback threw: "
                    + thrown.getMessage())
                .field("repository.name", this.repoName)
                .field("url.path", key.string())
                .error(thrown)
                .log();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void enqueueEvent(
        final Key key, final Headers headers, final long size, final String owner
    ) {
        if (this.events.isEmpty()) {
            return;
        }
        try {
            final Optional<ProxyArtifactEvent> event =
                this.buildArtifactEvent(key, headers, size, owner);
            event.ifPresent(e -> {
                if (!this.events.get().offer(e)) {
                    com.auto1.pantera.metrics.EventsQueueMetrics
                        .recordDropped(this.repoName);
                }
            });
        } catch (final Throwable t) {
            EcsLogger.warn("com.auto1.pantera.cache")
                .message("Failed to enqueue proxy event; serve path unaffected")
                .eventCategory("process")
                .eventAction("queue_enqueue")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .log();
        }
    }

    private void trackUpstreamFailure(final Throwable error) {
        final String errorType;
        if (error instanceof TimeoutException) {
            errorType = "timeout";
        } else if (error instanceof ConnectException) {
            errorType = "connection_refused";
        } else {
            errorType = "unknown";
        }
        this.recordMetric(() ->
            com.auto1.pantera.metrics.PanteraMetrics.instance()
                .upstreamFailure(this.repoName, this.upstreamUrl, errorType)
        );
    }

    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.repoName, this.upstreamUrl, result, duration);
            }
        });
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }

    private void logDebug(final String message, final String path) {
        EcsLogger.debug("com.auto1.pantera." + this.repoType)
            .message(message)
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("repository.name", this.repoName)
            .field("url.path", path)
            .log();
    }

    /**
     * Strip {@code Content-Encoding} and {@code Content-Length} headers that indicate
     * the HTTP client already decoded the response body.
     *
     * <p>Jetty's {@code GZIPContentDecoder} (registered by default) auto-decodes gzip,
     * deflate and br response bodies but leaves the original {@code Content-Encoding}
     * header intact. Passing those headers through to callers creates a header/body
     * mismatch: the body is plain bytes while the header still claims it is compressed.
     * Any client that trusts the header will fail to inflate the body
     * ({@code Z_DATA_ERROR: zlib: incorrect header check}).
     *
     * <p>We strip {@code Content-Length} as well because it refers to the compressed
     * size, which no longer matches the decoded body length.
     *
     * @param headers Upstream response headers
     * @return Headers without Content-Encoding (gzip/deflate/br) and Content-Length
     */
    protected static Headers stripContentEncoding(final Headers headers) {
        final boolean hasDecoded = StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> "content-encoding".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .map(v -> v.toLowerCase(Locale.ROOT).trim())
            .anyMatch(v -> v.contains("gzip") || v.contains("deflate") || v.contains("br"));
        if (!hasDecoded) {
            return headers;
        }
        final List<Header> filtered = StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> !"content-encoding".equalsIgnoreCase(h.getKey())
                && !"content-length".equalsIgnoreCase(h.getKey()))
            .collect(Collectors.toList());
        return new Headers(filtered);
    }

    /**
     * Extract Last-Modified timestamp from response headers.
     * @param headers Response headers
     * @return Optional epoch millis
     */
    protected static Optional<Long> extractLastModified(final Headers headers) {
        try {
            return StreamSupport.stream(headers.spliterator(), false)
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(
                    DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)
                ).toEpochMilli());
        } catch (final DateTimeParseException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to parse Last-Modified header")
                .error(ex)
                .log();
            return Optional.empty();
        }
    }
}
