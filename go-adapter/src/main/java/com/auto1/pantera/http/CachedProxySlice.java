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
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.cooldown.GoLatestHandler;
import com.auto1.pantera.http.cooldown.GoListHandler;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Flowable;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
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
 * <p>Primary artifact writes (the {@code *.zip} module archives) flow
 * through {@link ProxyCacheWriter}, which streams the upstream body to
 * the client and the on-disk cache in a single pass and single-flights
 * concurrent cold fetches. There is <strong>no zip integrity
 * verification</strong>: the GOPROXY protocol defines no checksum
 * sidecar for module archives — the {@code .ziphash} path this class
 * previously fetched does not exist upstream, and even where a server
 * happened to answer it, an {@code h1:} dirhash is not a zip-byte
 * digest anyway. Genuine archive integrity (verifying the downloaded
 * bytes' {@code h1:} dirhash against the Go checksum database) returns
 * once the sumdb proxy lands (WS4-go). {@code *.info} and {@code *.mod}
 * paths have no upstream sidecars either and are handled by the legacy
 * {@code fetchThroughCache} flow unchanged.
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
     * Single-source-of-truth cache writer introduced by WI-07 (§9.5 of the
     * v2.2 target architecture). Streams the primary {@code *.zip} to the
     * client and the on-disk cache in a single pass and commits it
     * atomically. No sidecar is fetched or verified — the GOPROXY
     * protocol has no checksum sidecar for module archives; see the
     * class javadoc. Null when {@link #storage} is empty.
     */
    private final ProxyCacheWriter cacheWriter;

    /**
     * Per-key single-flight gate for the primary-artifact path (T-P08).
     * Concurrent callers for the same uncached {@code .zip} collapse to a
     * single upstream call; followers wait on the gate then re-enter
     * {@link #verifyAndServePrimary} which hits the now-warm cache.
     */
    private final SingleFlight<Key, Void> primarySingleFlight;

    /**
     * {@code /@latest} cooldown handler: intercepts {@code go get
     * <module>} (without a pseudo-version) resolution, rewrites the
     * response to the highest non-blocked version when the upstream
     * latest is in cooldown. Closes the unbounded-resolution gap left
     * by a {@code /@v/list}-only filter — the Go client does not hit
     * {@code /@v/list} on this path.
     */
    private final GoLatestHandler latestHandler;

    /**
     * {@code /@v/list} cooldown handler: filters blocked versions out of
     * the plain-text version list Go clients fetch for {@code go list
     * -m -versions &lt;module&gt;}, {@code go mod download}, and MVS
     * resolution. This is where {@code GoMetadataFilter} /
     * {@code GoMetadataParser} (registered via {@code CooldownWiring})
     * are actually consumed; without it the bundle would be dead
     * infrastructure and blocked versions would leak to the client.
     */
    private final GoListHandler listHandler;

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
        this.cacheWriter = storage
            .map(raw -> new ProxyCacheWriter(raw, rname, meterRegistry()))
            .orElse(null);
        this.primarySingleFlight = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
        this.latestHandler = new GoLatestHandler(
            client, cooldown, inspector, rtype, rname
        );
        this.listHandler = new GoListHandler(
            client, cooldown, inspector, rtype, rname
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        EcsLogger.info("com.auto1.pantera.http")
            .message("Processing Go proxy request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("url.path", path)
            .field("repository.name", this.rname)
            .field("log.source", "application")
            .log();

        if ("/".equals(path) || path.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Handling root path")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("log.source", "application")
                .log();
            return this.handleRootPath(line);
        }
        // Cooldown-aware @latest rewrite: intercepts before generic
        // fetchThroughCache so "go get <module>" (no pseudo-version) is
        // transparently redirected to the highest non-blocked version.
        if (this.latestHandler.matches(path)) {
            final String user = new Login(headers).getValue();
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Handling @latest via cooldown handler")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .field("repository.name", this.rname)
                .field("log.source", "application")
                .log();
            return this.latestHandler.handle(line, headers, user);
        }
        // Cooldown-aware @v/list filter: strips blocked versions so
        // "go list -m -versions" / MVS resolution never sees them.
        if (this.listHandler.matches(path)) {
            final String user = new Login(headers).getValue();
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Handling @v/list via cooldown handler")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("url.path", path)
                .field("repository.name", this.rname)
                .field("log.source", "application")
                .log();
            return this.listHandler.handle(line, headers, user);
        }
        final Key key = new KeyFromPath(path);
        final Matcher matcher = ARTIFACT.matcher(key.string());

        // For non-artifact paths (e.g., list endpoints), skip cooldown and cache directly
        if (!matcher.matches()) {
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Non-artifact path, skipping cooldown")
                .eventCategory("web")
                .eventAction("proxy_request")
                .field("package.name", key.string())
                .field("log.source", "application")
                .log();
            return this.fetchThroughCache(line, key, headers, Optional.empty(), Optional.empty());
        }

        // Extract artifact info and create cooldown request
        final String module = matcher.group("module");
        final String version = matcher.group("version");
        final String user = new Login(headers).getValue();
        // Captured before any async hop below so the access-audit record
        // reflects THIS request's correlation context, not whatever (or
        // nothing) is bound to the worker thread that eventually runs the
        // storage/network continuations.
        final AuditContext ctx = this.captureAuditContext(headers);
        EcsLogger.debug("com.auto1.pantera.http")
            .message("Go artifact request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("package.name", module)
            .field("package.version", version)
            .field("user.name", user)
            .field("log.source", "application")
            .log();

        // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
        // This ensures offline mode works - serve cached content even when upstream is down
        return this.cache.load(
            key,
            Remote.EMPTY,  // Just check cache existence
            CacheControl.Standard.ALWAYS
        ).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT - serve immediately without any network calls.
                // The artifact was already published to the DB the first
                // time it was cached — this is a read, not a publish. No
                // ProxyArtifactEvent here; audit as access instead.
                EcsLogger.info("com.auto1.pantera.http")
                    .message("Cache hit, serving cached artifact (offline-safe)")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", module)
                    .field("package.version", version)
                    .field("log.source", "application")
                    .log();
                if (key.string().endsWith(".zip")) {
                    AuditLogger.access(
                        ctx, this.rtype, this.rname, module, version,
                        cached.get().size().orElse(0L), user,
                        AuditLogger.OUTCOME_SUCCESS, null
                    );
                } else {
                    // .info / .mod — per-version resolution metadata, not
                    // artifact bytes: audit as resolution, not access.
                    AuditLogger.resolution(
                        ctx, this.rtype, this.rname, module, user, java.util.List.of()
                    );
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(cached.get())
                        .build()
                );
            }

            // Cache MISS - now we need network, evaluate cooldown
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Cache miss, evaluating cooldown")
                .eventCategory("web")
                .eventAction("cache_miss")
                .eventOutcome("success")
                .field("package.name", module)
                .field("package.version", version)
                .field("log.source", "application")
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
                        EcsLogger.info("com.auto1.pantera.http")
                            .message("Blocked Go artifact due to cooldown: " + result.block().orElseThrow().reason())
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .field("event.reason", "cooldown_active")
                            .field("package.name", module)
                            .field("package.version", version)
                            .field("log.source", "application")
                            .log();
                        if (key.string().endsWith(".zip")) {
                            AuditLogger.access(
                                ctx, this.rtype, this.rname, module, version, 0L, user,
                                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_COOLDOWN_ACTIVE
                            );
                        } else {
                            // Blocked .info / .mod: the client asked for
                            // version-resolution metadata and cooldown hid
                            // that version — resolution with it filtered.
                            AuditLogger.resolution(
                                ctx, this.rtype, this.rname, module, user,
                                java.util.List.of(version)
                            );
                        }
                        return CompletableFuture.completedFuture(
                            CooldownResponseRegistry.instance()
                                .getOrThrow(this.rtype)
                                .forbidden(result.block().orElseThrow())
                        );
                    }
                    EcsLogger.debug("com.auto1.pantera.http")
                        .message("Cooldown passed, proceeding with fetch")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .field("package.name", module)
                        .field("package.version", version)
                        .field("log.source", "application")
                        .log();
                    // Cooldown passed, proceed with fetch
                    // Get the release date for database event
                    return this.inspector.releaseDate(module, version)
                        .thenCompose(releaseDate -> {
                            EcsLogger.debug("com.auto1.pantera.http")
                                .message("Release date retrieved")
                                .eventCategory("web")
                                .eventAction("proxy_request")
                                .field("package.name", module)
                                .field("package.version", version)
                                .field("package.release_date", releaseDate.orElse(null))
                                .field("log.source", "application")
                                .log();
                            return this.fetchFromRemoteAndCache(
                                line,
                                key,
                                user,
                                Optional.of(module + "/@v/" + version),
                                releaseDate,
                                new AtomicReference<>(Headers.EMPTY),
                                ctx
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
                EcsLogger.debug("com.auto1.pantera.http")
                    .message("Cache hit, serving cached content")
                    .eventCategory("web")
                    .eventAction("cache_hit")
                    .eventOutcome("success")
                    .field("package.name", key.string())
                    .field("log.source", "application")
                    .log();
                // Record event for .zip files
                if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                    this.enqueueEvent(owner, artifactPath, releaseDate);
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(cached.get())
                        .build()
                );
            }
            // Cache MISS - fetch from remote with checksum validation
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Cache miss, fetching from remote")
                .eventCategory("web")
                .eventAction("cache_miss")
                .eventOutcome("success")
                .field("package.name", key.string())
                .field("log.source", "application")
                .log();
            return this.fetchFromRemoteAndCache(
                line, key, owner, artifactPath, releaseDate, rshdr,
                this.captureAuditContext(request)
            );
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
        final AtomicReference<Headers> rshdr,
        final AuditContext ctx
    ) {
        // WI-07 §9.5 — atomic stream-through cache write for Go module
        // archives. No sidecar is fetched or verified (see class javadoc);
        // *.info / *.mod have no sidecars either and fall through to the
        // legacy flow. Runs only when we have a file-backed storage.
        if (this.cacheWriter != null
            && this.storage.isPresent()
            && key.string().endsWith(".zip")) {
            return this.verifyAndServePrimary(line, key, owner, artifactPath, releaseDate, rshdr, ctx);
        }
        // Get checksum headers from remote HEAD for validation
        return new RepoHead(this.client)
            .head(line.uri().getPath())
            .exceptionally(err -> {
                // Network error during HEAD - log and continue with empty headers
                // This allows cache to work in degraded mode (no checksum validation)
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Remote HEAD failed, proceeding without checksum validation")
                    .eventCategory("web")
                    .eventAction("proxy_request")
                    .eventOutcome("success")
                    .field("event.reason", "degraded_response")
                    .field("package.name", key.string())
                    .field("error.message", err.getMessage())
                    .field("log.source", "application")
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
                                EcsLogger.warn("com.auto1.pantera.http")
                                    .message("Remote fetch failed")
                                    .eventCategory("web")
                                    .eventAction("proxy_request")
                                    .eventOutcome("failure")
                                    .field("package.name", key.string())
                                    .field("error.message", err.getMessage())
                                    .field("log.source", "application")
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
                        // Record database event ONLY after successful cache load for .zip files —
                        // genuine cache miss + successful upstream fetch, the only branch that
                        // should publish.
                        if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                            EcsLogger.debug("com.auto1.pantera.http")
                                .message("Attempting to enqueue Go proxy event")
                                .eventCategory("web")
                                .eventAction("proxy_request")
                                .field("package.name", key.string())
                                .field("file.path", artifactPath.get())
                                .field("user.name", owner)
                                .field("log.source", "application")
                                .log();
                            this.bindContext(ctx);
                            this.enqueueEvent(
                                owner,
                                artifactPath,
                                releaseDate.or(() -> this.parseLastModified(rshdr.get()))
                            );
                            this.auditZipAccess(
                                ctx, key, owner, content.get().size().orElse(0L),
                                AuditLogger.OUTCOME_SUCCESS, null
                            );
                        } else if (artifactPath.isPresent()) {
                            // .info / .mod fetched from upstream — per-version
                            // resolution metadata: audit as resolution.
                            final Matcher matched = ARTIFACT.matcher(key.string());
                            AuditLogger.resolution(
                                ctx, this.rtype, this.rname,
                                matched.matches() ? matched.group("module") : key.string(),
                                owner, java.util.List.of()
                            );
                        }
                        return ResponseBuilder.ok()
                            .headers(rshdr.get())
                            .body(content.get())
                            .build();
                    }
                    if (throwable != null) {
                        EcsLogger.error("com.auto1.pantera.http")
                            .message("Failed to fetch through cache")
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .error(throwable)
                            .field("log.source", "application")
                            .log();
                    } else {
                        EcsLogger.warn("com.auto1.pantera.http")
                            .message("Cache load returned empty, returning 404")
                            .eventCategory("web")
                            .eventAction("proxy_request")
                            .eventOutcome("failure")
                            .field("event.reason", "artifact_not_found")
                            .field("package.name", key.string())
                            .field("repository.name", this.rname)
                            .field("log.source", "application")
                            .log();
                    }
                    if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                        this.auditZipAccess(
                            ctx, key, owner, 0L,
                            AuditLogger.OUTCOME_FAILURE,
                            throwable != null
                                ? AuditLogger.REASON_UPSTREAM_UNAVAILABLE
                                : AuditLogger.REASON_NOT_FOUND
                        );
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
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Failed to parse Last-Modified header: " + ex.getParsedString())
                .eventCategory("web")
                .eventAction("header_parse")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        }
    }

    /**
     * Build an {@link AuditContext} for the current request. Reads the
     * internal {@code X-Pantera-Ctx-*} headers into MDC first (a no-op if
     * already populated by {@code EcsLoggingSlice} on the request thread;
     * a real restore on a worker thread that never had it).
     *
     * @param headers Inbound request headers
     * @return Context carrying whatever trace id / client IP could be resolved
     */
    private AuditContext captureAuditContext(final Headers headers) {
        RequestContextHeaders.bindToMdc(headers);
        return new AuditContext(MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP));
    }

    /**
     * Push an already-resolved {@link AuditContext} onto the current
     * thread's MDC. Used instead of {@link #captureAuditContext(Headers)}
     * at call sites several async hops removed from the request — those
     * only have the {@code AuditContext} captured earlier on the request
     * thread, not the original {@link Headers}, so there is nothing left
     * to re-derive it from. Required immediately before constructing a
     * {@link ProxyArtifactEvent}, whose constructor auto-captures
     * trace.id/client.ip from MDC.
     *
     * @param ctx Context to bind
     */
    private static void bindContext(final AuditContext ctx) {
        if (ctx.traceId() != null) {
            MDC.put(EcsMdc.TRACE_ID, ctx.traceId());
        }
        if (ctx.clientIp() != null) {
            MDC.put(EcsMdc.CLIENT_IP, ctx.clientIp());
        }
    }

    /**
     * Emit an {@link AuditLogger#access} event for a {@code *.zip} module
     * archive, deriving module/version from {@code key} via the same
     * {@link #ARTIFACT} pattern {@link #response} uses.
     */
    private void auditZipAccess(
        final AuditContext ctx, final Key key, final String owner,
        final long size, final String outcome, final String reason
    ) {
        final Matcher matcher = ARTIFACT.matcher(key.string());
        final String name = matcher.matches() ? matcher.group("module") : key.string();
        final String version = matcher.matches() ? matcher.group("version") : null;
        AuditLogger.access(ctx, this.rtype, this.rname, name, version, size, owner, outcome, reason);
    }

    /**
     * Enqueue artifact event for metadata processing.
     * Only enqueues for actual artifacts (not list endpoints).
     *
     * @param owner Owner username
     * @param artifactPath Optional artifact path (module/@v/version)
     * @param releaseDate Optional release date
     */
    private void enqueueEvent(
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
            EcsLogger.error("com.auto1.pantera.http")
                .message("Events queue is NOT present - cannot enqueue events")
                .eventCategory("web")
                .eventAction("proxy_request")
                .eventOutcome("failure")
                .field("log.source", "application")
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
                EcsLogger.debug("com.auto1.pantera.http")
                    .message("Successfully enqueued Go proxy event (queue size: " + queue.size() + ")")
                    .eventCategory("web")
                    .eventAction("proxy_request")
                    .field("package.name", key.string())
                    .field("repository.name", this.rname)
                    .field("user.name", owner)
                    .field("package.release_date", release.map(Object::toString).orElse(null))
                    .field("log.source", "application")
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
                .<CacheControl>map(sto -> new CacheTimeControl(sto))
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

    // ===== T-P08: stream-through primary path =====

    /**
     * Primary-artifact flow (T-P08) for {@code *.zip} module archives. On
     * cache hit, serves from raw storage. On miss, the cache-miss leg is
     * single-flighted: the leader streams upstream → tee → client +
     * storage in a single pass via
     * {@link ProxyCacheWriter#streamThroughAndCommit}; followers wait on
     * the gate then re-enter against the now-warm cache.
     *
     * <p>Trade-off vs. the legacy buffered path: integrity failures no
     * longer fail-closed for the in-flight client (bytes are already in
     * the response stream when the sidecar comparison runs) — the cache
     * stays empty so the next request re-fetches cleanly. Matches the
     * Maven primary-path decision.
     */
    private CompletableFuture<Response> verifyAndServePrimary(
        final RequestLine line,
        final Key key,
        final String owner,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate,
        final AtomicReference<Headers> rshdr,
        final AuditContext auditCtx
    ) {
        final Storage raw = this.storage.orElseThrow();
        return raw.exists(key).thenCompose(present -> {
            if (present) {
                // Cache hit (either the outer check in fetchFromRemoteAndCache
                // raced a concurrent writer, or this is a single-flight
                // follower re-entering against the now-warm cache). The
                // artifact was already published when it was first cached —
                // this is a read, not a publish. No ProxyArtifactEvent here;
                // audit as access instead.
                if (artifactPath.isPresent()) {
                    this.auditZipAccess(
                        auditCtx, key, owner, 0L, AuditLogger.OUTCOME_SUCCESS, null
                    );
                }
                return this.serveFromCache(raw, key);
            }
            // T-P08: single-flight the cache-miss leg. Leader streams;
            // followers re-enter verifyAndServePrimary against the warm cache.
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
                return this.streamPrimary(
                    line, key, owner, artifactPath, releaseDate, rshdr, leaderGate, auditCtx
                );
            }
            return gate.exceptionally(err -> null).thenCompose(ignored ->
                this.verifyAndServePrimary(line, key, owner, artifactPath, releaseDate, rshdr, auditCtx)
            );
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.http")
                .message("Go primary-artifact verify-and-serve failed; returning 502")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("repository.name", this.rname)
                .field("url.path", key.string())
                .error(err)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badGateway().build();
        }).toCompletableFuture();
    }

    /**
     * Stream-through primary fetch (T-P08): tee upstream body bytes to the
     * client and the cache temp file in a single pass via
     * {@link ProxyCacheWriter#streamThroughAndCommit}. The
     * {@code leaderGate} resolves when the cache write is durable so
     * followers parked on {@link #primarySingleFlight} can re-enter
     * {@link #verifyAndServePrimary} against the warm cache.
     */
    private CompletableFuture<Response> streamPrimary(
        final RequestLine line,
        final Key key,
        final String owner,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate,
        final AtomicReference<Headers> rshdr,
        final CompletableFuture<Void> leaderGate,
        final AuditContext auditCtx
    ) {
        final RequestContext ctx = new RequestContext(
            org.apache.logging.log4j.ThreadContext.get("trace.id"),
            null,
            this.rname,
            key.string()
        );
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    resp.body().asBytesFuture();
                    if (!leaderGate.isDone()) {
                        leaderGate.complete(null);
                    }
                    final int status = resp.status().code();
                    if (artifactPath.isPresent()) {
                        this.auditZipAccess(
                            auditCtx, key, owner, 0L, AuditLogger.OUTCOME_FAILURE,
                            status >= 400 && status < 500
                                ? AuditLogger.REASON_NOT_FOUND
                                : AuditLogger.REASON_UPSTREAM_UNAVAILABLE
                        );
                    }
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
                rshdr.set(resp.headers());
                return this.cacheWriter.streamThroughAndCommit(
                    key,
                    key.string(),
                    resp.body().size(),
                    resp.body(),
                    // No sidecar: the GOPROXY protocol has no checksum
                    // file for module archives (see class javadoc). An
                    // empty map disables sidecar verification entirely —
                    // the write commits as soon as the stream completes.
                    new EnumMap<>(ChecksumAlgo.class),
                    Collections.emptySet(),
                    ctx
                ).toCompletableFuture().thenApply(result -> {
                    if (result instanceof Result.Err<ProxyCacheWriter.StreamedArtifact> err) {
                        if (!leaderGate.isDone()) {
                            leaderGate.complete(null);
                        }
                        if (artifactPath.isPresent()) {
                            this.auditZipAccess(
                                auditCtx, key, owner, 0L, AuditLogger.OUTCOME_FAILURE,
                                err.fault() instanceof Fault.UpstreamIntegrity
                                    ? AuditLogger.REASON_CHECKSUM_MISMATCH
                                    : AuditLogger.REASON_UPSTREAM_UNAVAILABLE
                            );
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
                    if (artifactPath.isPresent()) {
                        // Genuine cache miss + successful upstream fetch —
                        // the only branch that should publish.
                        this.bindContext(auditCtx);
                        this.enqueueEvent(
                            owner, artifactPath,
                            releaseDate.or(() -> this.parseLastModified(rshdr.get()))
                        );
                        this.auditZipAccess(
                            auditCtx, key, owner, artifact.body().size().orElse(0L),
                            AuditLogger.OUTCOME_SUCCESS, null
                        );
                    }
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
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Go stream-through primary fetch failed; returning 502")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.rname)
                    .field("url.path", key.string())
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.badGateway()
                    .textBody("Upstream temporarily unavailable")
                    .build();
            });
    }

    /**
     * Serve the primary from storage after a successful atomic write.
     */
    private CompletionStage<Response> serveFromCache(final Storage raw, final Key key) {
        return raw.value(key).thenApply(content ->
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
            EcsLogger.debug("com.auto1.pantera.http")
                .message("MicrometerMetrics registry unavailable; writer will run without metrics")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
        return null;
    }

}
