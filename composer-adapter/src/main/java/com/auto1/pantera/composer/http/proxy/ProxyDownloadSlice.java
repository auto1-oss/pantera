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
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.time.Instant;

/**
 * Slice for downloading actual package zip files through proxy.
 * Emits events to database when packages are actually downloaded.
 *
 * <p>A cache miss is served <b>stream-through</b>: the upstream body is
 * teed to the client and to the cache in a single pass by
 * {@link ProxyCacheWriter#streamThroughAndCommit} (2.2.9 — the archive is
 * never materialised on heap), verified against the packument's declared
 * {@code dist.shasum} (SHA-1; WS4-composer.3 / S7 of
 * {@code 00-security-integrity-decisions.md}) before the cache commit, and
 * single-flighted per dist key (WS4-composer.4) so concurrent cold requests
 * for the same archive make exactly one upstream call. Because bytes are
 * already flowing to the client when the digest comparison runs, a mismatch
 * cannot turn the in-flight response into a 502; it keeps the cache empty
 * (the next request re-fetches cleanly), is logged, and is written to the
 * audit trail as a {@code checksum_mismatch} access failure — Composer
 * itself re-verifies {@code dist.shasum} on the bytes it receives.</p>
 *
 * <p><b>Trace context contract.</b> Trace context (trace.id / span.id /
 * span.parent.id) is inherited from the {@code EcsLoggingSlice} MDC scope
 * set at request entry. Any async hop introduced in this slice MUST use
 * {@code ContextualExecutor.contextualize(...)} (or an equivalent MDC
 * capture-and-restore) to preserve trace.id across the executor
 * boundary — without it, log lines emitted from the worker thread
 * surface in Kibana with no trace correlation back to the originating
 * request.
 *
 * @since 1.0
 */
public final class ProxyDownloadSlice implements Slice {

    /**
     * Pattern to match rewritten download URLs.
     * The repo prefix is stripped by TrimPathSlice, so path arrives as:
     * /dist/{vendor}/{package}/{version}.zip  (new format)
     * /dist/{vendor}/{package}/{version}      (legacy, no extension)
     */
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
        "^/dist/(?<vendor>[^/]+)/(?<package>[^/]+)/(?<version>.+?)(?:\\.zip)?$"
    );

    /**
     * No sidecar algorithm is deferred: the only claim handed to the cache
     * writer is the packument's {@code dist.shasum} (SHA-1), and it must be
     * compared before the streamed archive is committed so that a mismatch
     * keeps the cache empty.
     */
    private static final Set<ChecksumAlgo> NO_DEFERRED_ALGOS = Set.of();

    /**
     * Remote slice to fetch from (for same-host requests).
     */
    private final Slice remote;

    /**
     * HTTP clients for building dynamic slices per host.
     */
    private final ClientSlices clients;

    /**
     * Remote base URI (used to detect same-host downloads).
     */
    private final URI remoteBase;


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
     * Storage to read cached metadata.
     */
    private final Storage storage;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Per-key single-flight gate for the dist-archive fetch
     * (WS4-composer.4). Concurrent callers for the same uncached archive
     * collapse to a single upstream call; followers wait on the leader's
     * gate — released once the leader's cache write is durable (or has
     * failed) — then re-enter {@link #fetchWithSingleFlight}, which now
     * serves the warm cache the leader wrote or retries cleanly.
     */
    private final SingleFlight<Key, Void> singleFlight;

    /**
     * Ctor.
     *
     * @param remote Remote slice (AuthClientSlice over remoteBase)
     * @param clients HTTP clients
     * @param remoteBase Remote base URI
     * @param events Events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param storage Storage for reading cached metadata
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    public ProxyDownloadSlice(
        final Slice remote,
        final ClientSlices clients,
        final URI remoteBase,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final Storage storage,
        final CooldownService cooldown,
        final CooldownInspector inspector
    ) {
        this.remote = remote;
        this.clients = clients;
        this.remoteBase = remoteBase;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.storage = storage;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.singleFlight = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (line.method() == RqMethod.HEAD) {
            return this.headAsGet(line, headers, body);
        }
        // Captured before any async hop below so the access-audit record
        // reflects THIS request's correlation context, not whatever (or
        // nothing) is bound to the worker thread that eventually runs the
        // storage/network continuations.
        final AuditContext ctx = this.captureAuditContext(headers);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String path = line.uri().getPath();
            EcsLogger.info("com.auto1.pantera.composer")
                .message("ProxyDownloadSlice handling request")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("url.path", path)
                .field("http.request.method", line.method().value())
                .field("log.source", "http")
                .log();
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Full request URI")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("url.full", line.uri().toString())
                .field("log.source", "application")
                .log();

            // Extract package info from rewritten URL
            final Matcher matcher = DOWNLOAD_PATTERN.matcher(path);
            if (!matcher.matches()) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("URL doesn't match download pattern (expected pattern: /dist/vendor/package/version)")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("url.path", path)
                    .field("log.source", "application")
                    .log();
                // Still proxy to remote in case it's a valid request
                return this.remote.response(line, Headers.EMPTY, Content.EMPTY);
            }

            final String vendor = matcher.group("vendor");
            final String pkg = matcher.group("package");
            final String version = matcher.group("version");
            final String packageName = vendor + "/" + pkg;

            EcsLogger.info("com.auto1.pantera.composer")
                .message("Download request for package")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("log.source", "application")
                .log();

            // Evaluate cooldown before proceeding
            final String owner = new Login(headers).getValue();
            // Keyed like the metadata handlers (ComposerMetadataRequestDetector):
            // Composer names are case-insensitive, one block row per package.
            final CooldownRequest cdreq = new CooldownRequest(
                this.rtype,
                this.rname,
                packageName.toLowerCase(Locale.ROOT),
                version,
                owner,
                Instant.now()
            );

            // Cache-first: check local storage before network calls
            // New format uses .zip extension; also check legacy key without it.
            // A dev-branch dist requested for a specific commit (?ref=) is
            // cached per reference and never answered from the version-only
            // keys, which hold whatever commit the branch pointed at before.
            final DevDistReference refs = new DevDistReference();
            final Optional<String> ref = refs.requested(version, line.uri().getRawQuery());
            final Key distKey = ref
                .map(r -> refs.key(vendor, pkg, version, r))
                .orElseGet(() -> new Key.From("dist", vendor, pkg, version + ".zip"));
            final Key legacyKey = new Key.From("dist", vendor, pkg, version);
            return this.storage.exists(distKey).thenCompose(cached -> {
                if (cached) {
                    return CompletableFuture.completedFuture(distKey);
                }
                if (ref.isPresent()) {
                    return CompletableFuture.completedFuture((Key) null);
                }
                // Fall back to legacy key (no .zip)
                return this.storage.exists(legacyKey).thenApply(
                    legacy -> legacy ? legacyKey : null
                );
            }).thenCompose(foundKey -> {
                if (foundKey != null) {
                    // Cache hit: artifact was already published to the DB
                    // the first time it was cached — this is a read, not a
                    // publish. No ProxyArtifactEvent here; audit as access.
                    return this.serveCacheHit(foundKey, ctx, packageName, version, headers);
                }
                // Cache miss — evaluate cooldown, then fetch from upstream
                return this.cooldown.evaluate(cdreq, this.inspector).thenCompose(result -> {
                    if (result.blocked()) {
                        EcsLogger.info("com.auto1.pantera.composer")
                            .message("Cooldown blocked download")
                            .eventCategory("web")
                            .eventAction("proxy_download")
                            .eventOutcome("failure")
                            .field("event.reason", "cooldown_active")
                            .field("package.name", packageName)
                            .field("package.version", version)
                            .field("log.source", "application")
                            .log();
                        AuditLogger.access(
                            ctx, this.rtype, this.rname, packageName, version, 0L,
                            owner, AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_COOLDOWN_ACTIVE
                        );
                        return CompletableFuture.completedFuture(
                            CooldownResponseRegistry.instance()
                                .getOrThrow(this.rtype)
                                .forbidden(result.block().orElseThrow())
                        );
                    }
                    return this.fetchAndCache(
                        line, headers, ctx, packageName, version, distKey, ref
                    );
                });
            });
        });
    }

    /**
     * HEAD support (WS4-composer.8): resolve exactly as GET, then drop the
     * body before returning so the client sees the same status/headers
     * without the archive bytes (RFC 9110 &sect;9.3.2). The GET path
     * already performs a genuine cache existence check for both new-format
     * and legacy dist keys, so HEAD gets the same answer a GET would —
     * including triggering (and single-flighting) a cold fetch when the
     * archive is not yet cached, matching the acceptance criterion that
     * HEAD of an absent artifact returns the same status a GET would.
     */
    private CompletableFuture<Response> headAsGet(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final RequestLine asGet = new RequestLine(RqMethod.GET, line.uri(), line.version());
        return this.response(asGet, headers, body).thenCompose(resp ->
            resp.body().asBytesFuture().thenApply(
                ignored -> new Response(resp.status(), resp.headers(), Content.EMPTY)
            )
        );
    }

    /**
     * Resolve the dist location (upstream URL + declared {@code dist.shasum})
     * from the cached metadata, then fetch, verify and cache the archive
     * (single-flighted per {@code distKey} — WS4-composer.4) and serve it.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final Optional<String> ref
    ) {
        final String owner = new Login(headers).getValue();
        return this.resolveDist(packageName, version, ref).thenCompose(dist -> {
            if (dist.isEmpty()) {
                EcsLogger.error("com.auto1.pantera.composer")
                    .message("Could not find original URL for package")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .field("log.source", "application")
                    .log();
                AuditLogger.access(
                    ctx, this.rtype, this.rname, packageName, version, 0L,
                    owner, AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                );
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return this.fetchWithSingleFlight(
                line, headers, ctx, packageName, version, distKey, dist.get()
            );
        });
    }

    /**
     * Single-flight gate around the leader fetch (WS4-composer.4): the
     * first caller for an uncached {@code distKey} becomes the leader and
     * performs {@link #leaderFetch}; concurrent followers wait for the
     * leader's gate then re-enter this method, which now either serves the
     * warm cache the leader wrote or — if the leader's fetch failed
     * integrity verification or upstream was unavailable — retries as a
     * fresh leader.
     */
    private CompletableFuture<Response> fetchWithSingleFlight(
        final RequestLine line,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final DistLocation dist
    ) {
        return this.storage.exists(distKey).thenCompose(present -> {
            if (present) {
                return this.serveCacheHit(distKey, ctx, packageName, version, headers);
            }
            final boolean[] isLeader = {false};
            final CompletableFuture<Void> leaderGate = new CompletableFuture<>();
            final CompletableFuture<Void> gate = this.singleFlight.load(
                distKey,
                () -> {
                    isLeader[0] = true;
                    return leaderGate;
                }
            );
            if (isLeader[0]) {
                return this.leaderFetch(
                    line, headers, ctx, packageName, version, distKey, dist, leaderGate
                );
            }
            return gate.exceptionally(err -> null).thenCompose(
                ignored -> this.fetchWithSingleFlight(
                    line, headers, ctx, packageName, version, distKey, dist
                )
            );
        });
    }

    /**
     * Serve a dist archive already present in storage (cache hit — either
     * the fast-path check in {@link #response} or a single-flight follower
     * re-entering after the leader committed the cache write).
     */
    private CompletableFuture<Response> serveCacheHit(
        final Key foundKey,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Headers headers
    ) {
        final String owner = new Login(headers).getValue();
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Cache HIT for dist artifact")
            .eventCategory("web")
            .eventAction("cache_hit")
            .eventOutcome("success")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("log.source", "application")
            .log();
        return this.storage.value(foundKey).thenApply(content -> {
            AuditLogger.access(
                ctx, this.rtype, this.rname, packageName, version,
                content.size().orElse(0L), owner,
                AuditLogger.OUTCOME_SUCCESS, null
            );
            return ResponseBuilder.ok()
                .header("Content-Type", "application/zip")
                .body(content)
                .build();
        });
    }

    /**
     * Leader-only upstream fetch: dial the dist's real host (the configured
     * upstream, or — for a cross-host dist — a per-host client, subject to
     * the egress policy), relay a non-2xx upstream answer unchanged, and
     * hand a 2xx body to {@link #streamThrough}. Every exit releases the
     * single-flight gate so parked followers never wait on a fetch that is
     * not going to populate the cache.
     */
    private CompletableFuture<Response> leaderFetch(
        final RequestLine line,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final DistLocation dist,
        final CompletableFuture<Void> leaderGate
    ) {
        final String owner = new Login(headers).getValue();
        final URI ouri = URI.create(dist.url());
        final Slice target;
        if (sameHost(this.remoteBase, ouri)) {
            target = this.remote;
        } else {
            // SECURITY (2.2.9): dist.url is publisher-influenced metadata.
            // A cross-host dist is only dialed when the egress policy
            // allows the destination (the Jetty resolver re-checks after
            // DNS); a denied destination is an upstream failure.
            final Optional<String> denied = ProxyDownloadSlice.egressDenial(ouri);
            if (denied.isPresent()) {
                leaderGate.complete(null);
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("dist.url refused by egress policy: " + denied.get())
                    .eventCategory("network")
                    .eventAction("egress_denied")
                    .eventOutcome("failure")
                    .field("url.full", dist.url())
                    .field("destination.address", ouri.getHost())
                    .field("event.reason", denied.get())
                    .field("repository.name", this.rname)
                    .field("log.source", "application")
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badGateway()
                        .textBody("dist destination not allowed")
                        .build()
                );
            }
            target = new UriClientSlice(this.clients, baseOf(ouri));
        }
        final String pathWithQuery = buildPathWithQuery(ouri);
        final RequestLine newLine = RequestLine.from(
            line.method().value() + " " + pathWithQuery + " " + line.version()
        );
        final Headers out = buildUpstreamHeaders(headers);
        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Fetching dist from upstream")
            .eventCategory("web")
            .eventAction("proxy_download")
            .field("url.original", dist.url())
            .field("log.source", "application")
            .log();
        return target.response(newLine, out, Content.EMPTY).thenCompose(response -> {
            if (!response.status().success()) {
                leaderGate.complete(null);
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Upstream download failed")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .field("http.response.status_code", response.status().code())
                    .field("log.source", "http")
                    .log();
                AuditLogger.access(
                    ctx, this.rtype, this.rname, packageName, version, 0L, owner,
                    AuditLogger.OUTCOME_FAILURE,
                    response.status().code() == 404
                        ? AuditLogger.REASON_NOT_FOUND
                        : AuditLogger.REASON_UPSTREAM_UNAVAILABLE
                );
                return CompletableFuture.completedFuture(response);
            }
            return this.streamThrough(
                headers, ctx, packageName, version, distKey, dist, response, leaderGate
            );
        }).exceptionally(err -> {
            leaderGate.complete(null);
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Composer dist fetch failed; returning 502")
                .eventCategory("web")
                .eventAction("proxy_download")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("repository.name", this.rname)
                .error(err)
                .field("log.source", "application")
                .log();
            AuditLogger.access(
                ctx, this.rtype, this.rname, packageName, version, 0L, owner,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_UPSTREAM_UNAVAILABLE
            );
            return ProxyDownloadSlice.upstreamFailure(err);
        });
    }

    /**
     * STREAM the dist through to the client and the cache at once. Before
     * 2.2.9 the whole upstream body was materialised with
     * {@code asBytesFuture()} — an artifact of any size the upstream chose
     * to send sat in heap before the first byte reached anyone
     * (resource-dos F53). {@link ProxyCacheWriter} tees the upstream stream
     * to the response and to a temp file that commits on completion — only
     * after the digest it computed over the streamed bytes matched the
     * packument's {@code dist.shasum} claim (when one is declared).
     */
    private CompletableFuture<Response> streamThrough(
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final DistLocation dist,
        final Response response,
        final CompletableFuture<Void> leaderGate
    ) {
        final String owner = new Login(headers).getValue();
        final ProxyCacheWriter writer = new ProxyCacheWriter(this.storage, this.rname);
        final RequestContext rctx = new RequestContext(
            ctx.traceId(), null, this.rname, dist.url()
        );
        return writer.streamThroughAndCommit(
            distKey, dist.url(), response.body().size(), response.body(),
            ProxyDownloadSlice.shasumClaim(dist), NO_DEFERRED_ALGOS, rctx
        ).toCompletableFuture().thenApply(result -> {
            if (result instanceof Result.Err<?>) {
                // The writer could not even open its temp file; the upstream
                // body was never subscribed, so it is released here.
                leaderGate.complete(null);
                response.body().discard();
                AuditLogger.access(
                    ctx, this.rtype, this.rname, packageName, version, 0L,
                    owner, AuditLogger.OUTCOME_FAILURE,
                    AuditLogger.REASON_UPSTREAM_UNAVAILABLE
                );
                return ResponseBuilder.badGateway()
                    .textBody("Upstream temporarily unavailable")
                    .build();
            }
            @SuppressWarnings("unchecked")
            final ProxyCacheWriter.StreamedArtifact streamed =
                ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
            this.afterStream(
                streamed, headers, ctx, packageName, version, distKey, owner, leaderGate
            );
            return ResponseBuilder.ok()
                .header("Content-Type", "application/zip")
                .body(streamed.body())
                .build();
        });
    }

    /**
     * Post-stream bookkeeping, run when the tee terminates (i.e. once the
     * client has consumed the body): release the single-flight followers,
     * then publish + audit only if the cache write actually committed — a
     * genuine cache miss + successful, integrity-verified upstream fetch is
     * the only branch that should publish. The size is read back from the
     * committed cache entry: upstreams that stream without Content-Length
     * (GitHub zipballs) declare no size, and 0 is not a valid audit
     * package.size. An integrity mismatch is recorded as a failed access:
     * the bytes reached the client, nothing was cached.
     */
    private void afterStream(
        final ProxyCacheWriter.StreamedArtifact streamed,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final String owner,
        final CompletableFuture<Void> leaderGate
    ) {
        streamed.verificationOutcome().toCompletableFuture()
            .thenCompose(outcome -> {
                if (outcome instanceof Result.Ok<?>) {
                    return this.committedSize(distKey).thenApply(Optional::of);
                }
                if (outcome instanceof Result.Err<?> err
                    && err.fault() instanceof Fault.UpstreamIntegrity) {
                    this.rejectedMismatch(ctx, packageName, version, owner);
                }
                return CompletableFuture.completedFuture(Optional.<Long>empty());
            })
            .whenComplete((committed, err) -> {
                leaderGate.complete(null);
                if (err == null && committed.isPresent()) {
                    this.committed(committed.get(), headers, ctx, packageName, version, owner);
                }
            });
    }

    /**
     * The streamed archive committed to the cache: log, publish the proxy
     * artifact event and write the successful access-audit record.
     */
    private void committed(
        final long size,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final String owner
    ) {
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Cached streamed dist artifact to storage")
            .eventCategory("web")
            .eventAction("proxy_download")
            .eventOutcome("success")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.size", size)
            .field("log.source", "application")
            .log();
        this.emitEvent(packageName, version, headers);
        AuditLogger.access(
            ctx, this.rtype, this.rname, packageName, version,
            size, owner, AuditLogger.OUTCOME_SUCCESS, null
        );
    }

    /**
     * The streamed bytes disagreed with the packument's {@code dist.shasum}
     * (WS4-composer.3 / S7): the writer dropped its temp file, so nothing
     * was cached and the next request re-fetches cleanly. Logged as a state
     * transition and audited as a failed access with
     * {@code checksum_mismatch}.
     */
    private void rejectedMismatch(
        final AuditContext ctx,
        final String packageName,
        final String version,
        final String owner
    ) {
        EcsLogger.warn("com.auto1.pantera.composer")
            .message(
                "Composer dist integrity verification against the packument dist.shasum failed;"
                    + " the streamed bytes reached the client but nothing was cached"
            )
            .eventCategory("web")
            .eventAction("cache_write")
            .eventOutcome("failure")
            .field("event.reason", AuditLogger.REASON_CHECKSUM_MISMATCH)
            .field("repository.name", this.rname)
            .field("package.name", packageName)
            .field("package.version", version)
            .field("log.source", "application")
            .log();
        AuditLogger.access(
            ctx, this.rtype, this.rname, packageName, version, 0L, owner,
            AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
        );
    }

    /**
     * The packument's {@code dist.shasum}, handed to the cache writer as an
     * in-memory SHA-1 "sidecar" so the tee compares it against the digest it
     * computes over the streamed bytes and refuses to commit a mismatch. No
     * phantom {@code .sha256} HTTP fetch — Composer has no such resource;
     * the real claim is inline in the packument. Empty when the packument
     * declares no claim: there is nothing to verify against and the archive
     * is cached as streamed.
     *
     * @param dist Resolved dist location
     * @return Sidecar suppliers keyed by algorithm (at most SHA-1)
     */
    private static Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> shasumClaim(
        final DistLocation dist
    ) {
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> claims =
            new EnumMap<>(ChecksumAlgo.class);
        dist.shasum().ifPresent(
            claim -> claims.put(
                ChecksumAlgo.SHA1,
                () -> CompletableFuture.completedFuture(
                    Optional.<InputStream>of(
                        new ByteArrayInputStream(claim.getBytes(StandardCharsets.US_ASCII))
                    )
                )
            )
        );
        return claims;
    }

    /**
     * The 502 answered when the upstream call itself failed. Preserves the
     * outbound circuit breaker's fast-fail marker (and its Retry-After
     * hint) so a php-group does not convict this member on fabricated
     * evidence — see {@link UpstreamCircuitOpenException}.
     *
     * @param err Failure, possibly wrapped
     * @return 502 response
     */
    private static Response upstreamFailure(final Throwable err) {
        final ResponseBuilder builder = ResponseBuilder.badGateway();
        Throwable cur = err;
        while (cur != null) {
            if (cur instanceof UpstreamCircuitOpenException open) {
                builder.header(UpstreamCircuitOpenException.HEADER, "true");
                if (open.retryAfterSeconds() > 0L) {
                    builder.header("Retry-After", Long.toString(open.retryAfterSeconds()));
                }
                break;
            }
            cur = cur.getCause();
        }
        return builder.textBody("Upstream temporarily unavailable").build();
    }

    /**
     * Build a minimal set of upstream headers.
     * Copies User-Agent from client if present; otherwise sets a default.
     * Adds a generic Accept header suitable for binary content.
     */
    private static Headers buildUpstreamHeaders(final Headers incoming) {
        final Headers out = new Headers();
        final java.util.List<com.auto1.pantera.http.headers.Header> ua = incoming.find("User-Agent");
        if (!ua.isEmpty()) {
            out.add(ua.getFirst(), true);
        } else {
            out.add("User-Agent",
                com.auto1.pantera.http.PanteraUserAgent.userAgentWithComponent("composer-proxy"));
        }
        out.add("Accept", "application/octet-stream, */*");
        return out;
    }

    /**
     * Name-level / literal-IP egress check for a cross-host dist (no DNS —
     * this runs on the reactive path; the resolver guards resolved names).
     * Judged by the live admin egress policy (DB-backed, env fallback), the
     * same one the outbound resolver enforces.
     * @param uri Dist URI
     * @return Reason when denied, else empty
     */
    private static Optional<String> egressDenial(final URI uri) {
        final com.auto1.pantera.http.client.egress.EgressPolicy policy =
            com.auto1.pantera.http.client.egress.EgressSettingsRegistry.policy().get();
        final String host = uri.getHost();
        final Optional<String> byName = policy.hostRejection(host);
        if (byName.isPresent()) {
            return byName;
        }
        // DNS-free: only a strictly valid IP literal is parsed; a hostname
        // (or a malformed numeric host) is left to the egress resolver.
        return policy.literalRejection(host);
    }

    /**
     * Build base URI (scheme://host[:port]) for given URI.
     *
     * @param uri Input URI
     * @return Base URI
     */
    private static URI baseOf(final URI uri) {
        final int port = uri.getPort();
        final String auth = (port == -1)
            ? String.format("%s://%s", uri.getScheme(), uri.getHost())
            : String.format("%s://%s:%d", uri.getScheme(), uri.getHost(), port);
        return URI.create(auth);
    }

    /**
     * Build path with optional query for request line.
     *
     * @param uri URI
     * @return Path with query
     */
    private static String buildPathWithQuery(final URI uri) {
        final String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        final String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return path;
        }
        return path + "?" + query;
    }

    /**
     * Check if two URIs point to the same host:port and scheme.
     *
     * @param a First URI
     * @param b Second URI
     * @return True if same scheme, host and port
     */
    private static boolean sameHost(final URI a, final URI b) {
        return safeEq(a.getScheme(), b.getScheme())
            && safeEq(a.getHost(), b.getHost())
            && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(final URI u) {
        final int p = u.getPort();
        if (p != -1) {
            return p;
        }
        final String scheme = u.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private static boolean safeEq(final String s1, final String s2) {
        return s1 == null ? s2 == null : s1.equalsIgnoreCase(s2);
    }

    /**
     * A resolved dist location: the upstream URL to fetch the archive
     * from, plus Composer's declared integrity claim, if any.
     *
     * <p>Despite the field's historically confusing name, Composer's
     * {@code dist.shasum} is a <b>SHA-1</b> hex digest — Composer's own
     * {@code ArchiveDownloader} verifies downloads with
     * {@code hash_file('sha1', ...)} against
     * {@code Package::getDistSha1Checksum()}. Verifying it as SHA-256 (as
     * an earlier draft of this feature assumed) would never match a real
     * Packagist-supplied claim and would permanently reject every
     * legitimate download that declares one.
     *
     * @param url Upstream URL to fetch the archive from
     * @param shasum Declared {@code dist.shasum} (SHA-1 hex), when present
     *  and non-blank
     */
    private record DistLocation(String url, Optional<String> shasum) {
    }

    /**
     * Resolve the dist location (URL + declared integrity claim) from
     * cached metadata.
     *
     * @param packageName Package name (vendor/package)
     * @param version Version
     * @param ref Requested dist reference (dev versions), if any
     * @return Resolved dist location, or empty if metadata/version/dist
     *  could not be found
     */
    private CompletableFuture<Optional<DistLocation>> resolveDist(
        final String packageName,
        final String version,
        final Optional<String> ref
    ) {
        // Metadata is cached by CachedProxySlice with .json extension. Stable
        // and dev-branch versions live in separate files (Composer v2 serves
        // dev branches from /p2/<pkg>~dev.json, cached as <pkg>~dev.json), so
        // a version absent from the stable file is looked up in the dev file.
        return this.distFrom(new Key.From(packageName + ".json"), packageName, version, ref)
            .thenCompose(found -> {
                if (found.isPresent()) {
                    return CompletableFuture.completedFuture(found);
                }
                return this.distFrom(
                    new Key.From(packageName + "~dev.json"), packageName, version, ref
                );
            });
    }

    /**
     * Resolve a version's dist location from one cached metadata file.
     *
     * @param metadataKey Cached metadata file
     * @param packageName Package name ({@code vendor/pkg})
     * @param version Version
     * @param ref Requested dist reference: the version's current
     *     {@code dist.reference} must match it, otherwise the URL (which
     *     builds a different commit) is not returned
     * @return Dist location, or empty when the file or the version is absent
     */
    private CompletableFuture<Optional<DistLocation>> distFrom(
        final Key metadataKey,
        final String packageName,
        final String version,
        final Optional<String> ref
    ) {
        return this.storage.exists(metadataKey).thenCompose(exists -> {
            if (!exists) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Metadata not found for package")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .field("log.source", "application")
                    .log();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return this.storage.value(metadataKey).thenCompose(content ->
                content.asBytesFuture().thenApply(
                    bytes -> this.parseDist(bytes, packageName, version, ref)
                )
            );
        });
    }

    /**
     * Parse the {@code dist} object for {@code packageName}@{@code version}
     * out of a cached packument and extract its URL + declared shasum.
     */
    private Optional<DistLocation> parseDist(
        final byte[] bytes,
        final String packageName,
        final String version,
        final Optional<String> ref
    ) {
        try {
            final String json = new String(bytes, StandardCharsets.UTF_8);
            final JsonObject metadata = Json.createReader(new StringReader(json)).readObject();
            final Optional<JsonObject> dist = ProxyDownloadSlice.distObject(metadata, packageName, version);
            if (dist.isEmpty()) {
                return Optional.empty();
            }
            if (ref.isPresent() && !ref.get().equals(referenceOf(dist.get()))) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Requested dev dist reference is not the current one in metadata")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            }
            return this.distLocation(dist.get(), packageName, version);
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.composer")
                .message("Failed to parse metadata")
                .eventCategory("web")
                .eventAction("proxy_download")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .error(ex)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        }
    }

    /**
     * Locate the {@code dist} object for {@code packageName}@{@code version}
     * inside a packument, handling both v2 minified (array) and v1 (object)
     * version layouts.
     */
    private static Optional<JsonObject> distObject(
        final JsonObject metadata, final String packageName, final String version
    ) {
        final JsonObject packages = metadata.getJsonObject("packages");
        if (packages == null) {
            return Optional.empty();
        }
        final javax.json.JsonValue pkgVal = packages.get(packageName);
        if (pkgVal == null) {
            return Optional.empty();
        }
        JsonObject versionData = null;
        if (pkgVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
            for (final javax.json.JsonValue v : pkgVal.asJsonArray()) {
                final JsonObject vo = v.asJsonObject();
                if (versionEquals(vo.getString("version", ""), version)) {
                    versionData = vo;
                    break;
                }
            }
        } else {
            final JsonObject versions = pkgVal.asJsonObject();
            versionData = versions.getJsonObject(version);
            if (versionData == null) {
                // try normalized key without leading 'v'
                versionData = versions.getJsonObject(stripV(version));
            }
        }
        return versionData == null
            ? Optional.empty()
            : Optional.ofNullable(versionData.getJsonObject("dist"));
    }

    /**
     * Read the upstream URL and the declared {@code dist.shasum} claim out
     * of a version's {@code dist} object.
     *
     * @param dist Dist object of the requested version
     * @param packageName Package name (for logging)
     * @param version Version (for logging)
     * @return Dist location, or empty when the dist declares no URL
     */
    private Optional<DistLocation> distLocation(
        final JsonObject dist, final String packageName, final String version
    ) {
        // Get original URL from cached metadata
        // Cached file now has rewritten format with "original_url" field
        // containing the actual remote URL (GitHub/packagist)
        String originalUrl = null;
        if (dist.containsKey("original_url")) {
            originalUrl = dist.getString("original_url");
            EcsLogger.info("com.auto1.pantera.composer")
                .message("Using original_url from metadata")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("url.original", originalUrl)
                .field("log.source", "application")
                .log();
        } else if (dist.containsKey("url")) {
            // Fallback to "url" for backward compatibility
            originalUrl = dist.getString("url");
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("No original_url found in dist, using url field")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("url.original", originalUrl)
                .field("log.source", "application")
                .log();
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("No dist URL found for package")
                .eventCategory("web")
                .eventAction("proxy_download")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        }
        final Optional<String> shasum = Optional.ofNullable(dist.getString("shasum", null))
            .map(String::trim)
            .filter(claim -> !claim.isEmpty());
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Found original URL for package")
            .eventCategory("web")
            .eventAction("proxy_download")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("url.original", originalUrl)
            .field("log.source", "application")
            .log();
        return Optional.of(new DistLocation(originalUrl, shasum));
    }

    /**
     * Size of a committed cache entry; 0 when the storage cannot report it.
     *
     * @param key Cache key
     * @return Size in bytes
     */
    private CompletableFuture<Long> committedSize(final Key key) {
        return this.storage.metadata(key)
            .<Long>thenApply(
                meta -> meta.read(com.auto1.pantera.asto.Meta.OP_SIZE)
                    .map(Long::longValue).orElse(0L)
            )
            .exceptionally(err -> 0L);
    }

    /**
     * The {@code dist.reference} string of a dist, or null.
     *
     * @param dist Dist object
     * @return Reference or null
     */
    private static String referenceOf(final JsonObject dist) {
        final javax.json.JsonValue value = dist.get("reference");
        return value instanceof javax.json.JsonString str ? str.getString() : null;
    }

    private static boolean versionEquals(final String a, final String b) {
        return stripV(a).equals(stripV(b));
    }

    private static String stripV(final String v) {
        if (v == null) {
            return "";
        }
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /**
     * Emit event for downloaded package.
     *
     * @param packageName Package name
     * @param version Package version
     * @param headers Request headers
     */
    private void emitEvent(final String packageName, final String version, final Headers headers) {
        if (this.events.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Events queue is empty, skipping event")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
            return;
        }
        // Restore MDC on whatever thread this runs on (the storage-save
        // continuation may not be the request thread) so the
        // ProxyArtifactEvent ctor below auto-captures THIS request's
        // trace.id/client.ip instead of null or a stale leftover value.
        RequestContextHeaders.bindToMdc(headers);
        final String owner = new Login(headers).getValue();
        // Store key as "packageName/version" so processor knows which version was downloaded
        final Key eventKey = new Key.From(packageName, version);
        this.events.get().add(
            new ProxyArtifactEvent(
                eventKey,
                this.rname,
                owner,
                Optional.empty()  // No release date from download
            )
        );
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Emitted download event (queue size: " + this.events.get().size() + ")")
            .eventCategory("web")
            .eventAction("proxy_download")
            .eventOutcome("success")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("user.name", owner)
            .field("log.source", "application")
            .log();
    }

    /**
     * Build an {@link AuditContext} for the current request from its internal
     * {@code X-Pantera-Ctx-*} headers, which are authoritative on any thread
     * (the thread's MDC is never read: a pooled thread can hold another
     * request's values). The headers are also bound to this thread's MDC for
     * the application logs that follow.
     *
     * @param headers Inbound request headers
     * @return Context carrying the request's trace id / client IP
     */
    private AuditContext captureAuditContext(final Headers headers) {
        RequestContextHeaders.bindToMdc(headers);
        return new AuditContext(headers);
    }
}
