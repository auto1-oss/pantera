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
import com.auto1.pantera.http.cache.DigestComputer;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.slf4j.MDC;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.time.Instant;

/**
 * Slice for downloading actual package zip files through proxy.
 * Emits events to database when packages are actually downloaded.
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
     * Per-key single-flight gate for the primary dist-archive fetch
     * (WS4-composer.3/.4). Concurrent callers for the same uncached
     * archive collapse to a single upstream call; followers wait on the
     * gate then re-enter {@link #fetchWithSingleFlight} which now hits
     * the warm cache the leader wrote (or retries cleanly if the leader's
     * fetch failed integrity verification).
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
            final CooldownRequest cdreq = new CooldownRequest(
                this.rtype,
                this.rname,
                packageName,
                version,
                owner,
                Instant.now()
            );

            // Cache-first: check local storage before network calls
            // New format uses .zip extension; also check legacy key without it
            final Key distKey = new Key.From(
                "dist", vendor, pkg, version + ".zip"
            );
            final Key legacyKey = new Key.From("dist", vendor, pkg, version);
            return this.storage.exists(distKey).thenCompose(cached -> {
                if (cached) {
                    return CompletableFuture.completedFuture(distKey);
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
                        line, headers, ctx, packageName, version, distKey
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
     * Resolve the dist location from cached metadata, then fetch/verify/
     * cache it (single-flighted per {@code distKey} — WS4-composer.4) and
     * serve the result.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey
    ) {
        final String owner = new Login(headers).getValue();
        return this.resolveDist(packageName, version).thenCompose(distOpt -> {
            if (distOpt.isEmpty()) {
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
                line, headers, ctx, packageName, version, distKey, distOpt.get()
            );
        });
    }

    /**
     * Single-flight gate around the leader fetch (WS4-composer.4): the
     * first caller for an uncached {@code distKey} becomes the leader and
     * performs {@link #leaderFetchVerifyAndCache}; concurrent followers
     * wait for the leader's gate then re-enter this method, which now
     * either serves the warm cache the leader wrote or — if the leader's
     * fetch failed integrity verification or upstream was unavailable —
     * retries as a fresh leader.
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
                return this.leaderFetchVerifyAndCache(
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
     * Leader-only upstream fetch: buffer the archive, verify it against
     * the packument's declared {@code dist.shasum} (WS4-composer.3 / S7 of
     * {@code 00-security-integrity-decisions.md}), and — only on a clean
     * verification (or when Composer declared no claim to verify against)
     * — persist it to the cache. A mismatch rejects the whole write: the
     * cache stays empty and the client receives a 502 with
     * {@code X-Pantera-Fault}, so a corrupted upstream archive can never
     * poison the cache and the next request re-fetches cleanly. Unlike the
     * Maven WI-07 stream-through trade-off, bytes are buffered (not teed to
     * the client) precisely so verification can fail closed before any
     * byte reaches the caller — dist archives are small package artifacts,
     * not multi-gigabyte primaries, so the heap cost is bounded.
     */
    private CompletableFuture<Response> leaderFetchVerifyAndCache(
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
        final Slice target = sameHost(this.remoteBase, ouri)
            ? this.remote
            : new UriClientSlice(this.clients, baseOf(ouri));
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
                return response.body().asBytesFuture().thenApply(ignored -> {
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
                    return response;
                });
            }
            return response.body().asBytesFuture().thenCompose(
                bytes -> this.verifyAndPersist(
                    ctx, packageName, version, distKey, dist, owner, headers, bytes, leaderGate
                )
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
                .error(err)
                .field("log.source", "application")
                .log();
            AuditLogger.access(
                ctx, this.rtype, this.rname, packageName, version, 0L, owner,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_UPSTREAM_UNAVAILABLE
            );
            return ResponseBuilder.badGateway()
                .textBody("Upstream temporarily unavailable")
                .build();
        });
    }

    /**
     * Verify the fetched bytes against the declared {@code dist.shasum}
     * (SHA-1 hex — Composer's real integrity claim; see the class-level
     * note on {@link DistLocation}). On mismatch the write is rejected:
     * nothing is cached and the leader gate still releases so followers
     * can retry cleanly. On match (or no declared claim to verify), the
     * bytes are persisted and served.
     */
    private CompletableFuture<Response> verifyAndPersist(
        final AuditContext ctx,
        final String packageName,
        final String version,
        final Key distKey,
        final DistLocation dist,
        final String owner,
        final Headers headers,
        final byte[] bytes,
        final CompletableFuture<Void> leaderGate
    ) {
        final Optional<String> mismatch = verifyShasum(dist.shasum(), bytes);
        if (mismatch.isPresent()) {
            leaderGate.complete(null);
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Composer dist integrity verification failed; not cached")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("package.version", version)
                .field("event.reason", AuditLogger.REASON_CHECKSUM_MISMATCH)
                .log();
            AuditLogger.access(
                ctx, this.rtype, this.rname, packageName, version, 0L, owner,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
            );
            return CompletableFuture.completedFuture(
                ResponseBuilder.badGateway()
                    .header("X-Pantera-Fault", "upstream-integrity:sha1")
                    .textBody("Upstream integrity verification failed")
                    .build()
            );
        }
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Caching dist artifact to storage")
            .eventCategory("web")
            .eventAction("proxy_download")
            .eventOutcome("success")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("file.size", bytes.length)
            .field("log.source", "application")
            .log();
        return this.storage.save(distKey, new Content.From(bytes)).thenApply(unused -> {
            leaderGate.complete(null);
            // Genuine cache miss + successful, integrity-verified upstream
            // fetch — the only branch that should publish.
            this.emitEvent(packageName, version, headers);
            AuditLogger.access(
                ctx, this.rtype, this.rname, packageName, version,
                bytes.length, owner, AuditLogger.OUTCOME_SUCCESS, null
            );
            return ResponseBuilder.ok()
                .header("Content-Type", "application/zip")
                .body(new Content.From(bytes))
                .build();
        });
    }

    /**
     * Verify {@code bytes} against a declared {@code dist.shasum} claim.
     *
     * @param declared Declared shasum, if Composer's metadata carried one
     * @param bytes Fetched archive bytes
     * @return Empty when there was no claim to verify or the claim
     *  matched; otherwise the locally-computed digest that disagreed
     *  (for logging), so the caller can reject the write.
     */
    private static Optional<String> verifyShasum(
        final Optional<String> declared, final byte[] bytes
    ) {
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        final String claim = declared.get().trim().toLowerCase(Locale.ROOT);
        if (claim.isEmpty()) {
            return Optional.empty();
        }
        final String computed = DigestComputer.compute(bytes, Set.of(DigestComputer.SHA1))
            .get(DigestComputer.SHA1);
        return claim.equals(computed) ? Optional.empty() : Optional.of(computed);
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
     * @return Resolved dist location, or empty if metadata/version/dist
     *  could not be found
     */
    private CompletableFuture<Optional<DistLocation>> resolveDist(
        final String packageName,
        final String version
    ) {
        // Metadata is cached by CachedProxySlice with .json extension
        final Key metadataKey = new Key.From(packageName + ".json");
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
                    bytes -> this.parseDistLocation(bytes, packageName, version)
                )
            );
        });
    }

    /**
     * Parse the {@code dist} object for {@code packageName}@{@code version}
     * out of a cached packument and extract its URL + declared shasum.
     */
    private Optional<DistLocation> parseDistLocation(
        final byte[] bytes, final String packageName, final String version
    ) {
        try {
            final String json = new String(bytes, StandardCharsets.UTF_8);
            final JsonObject metadata = Json.createReader(new StringReader(json)).readObject();
            final Optional<JsonObject> distOpt = findDistObject(metadata, packageName, version);
            if (distOpt.isEmpty()) {
                return Optional.empty();
            }
            final JsonObject dist = distOpt.get();
            // Cached file now has rewritten format with "original_url" field
            // containing the actual remote URL (GitHub/packagist); fall back
            // to "url" for backward compatibility with older cache entries.
            final String originalUrl = dist.containsKey("original_url")
                ? dist.getString("original_url", null)
                : dist.getString("url", null);
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
            final Optional<String> shasum = Optional.ofNullable(
                dist.getString("shasum", null)
            ).filter(s -> !s.isBlank());
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
    private static Optional<JsonObject> findDistObject(
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
}
