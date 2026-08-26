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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates cooldown-aware filtering of PyPI {@code /simple/<name>/}
 * responses — the PEP 503 HTML Simple Index surface that pip queries by
 * default.
 *
 * <p>This handler is where {@link PypiMetadataParser},
 * {@link PypiMetadataFilter} and {@link PypiMetadataRewriter} — the
 * trio registered via the cooldown SPI in {@code CooldownWiring} — are
 * actually consumed on the serve path. Prior to this class, the
 * registered {@code pypiBundle} was dead infrastructure: the PyPI
 * proxy slice filtered nothing on {@code /simple/} requests and blocked
 * versions leaked straight through to pip.</p>
 *
 * <p>Mirrors the Go handler pattern introduced in commit {@code 1eb53ceb}
 * ({@code GoListHandler} for {@code /@v/list}). The dispatch order in
 * the PyPI proxy slice must route JSON API requests through
 * {@link PypiJsonHandler} and Simple Index requests through this class
 * before the generic upstream-fetch flow.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Fetch {@code /simple/<name>/} from upstream via the shared
 *       slice (same auth / cache / resilience layers as artifact
 *       fetches).</li>
 *   <li>On non-2xx, forward status + body unchanged.</li>
 *   <li>Parse the HTML via {@link PypiMetadataParser}. On parse
 *       failure, pass upstream bytes through unchanged.</li>
 *   <li>Evaluate every parsed version against cooldown; collect
 *       the blocked set.</li>
 *   <li>Run the filter; re-serialise via {@link PypiMetadataRewriter}
 *       (PEP 503 HTML, {@code text/html} content type).</li>
 *   <li>If every version is blocked → 404, which is pip's convention
 *       for "package not available" and triggers its usual retry /
 *       error path cleanly.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class PypiSimpleHandler {

    /**
     * Upstream slice shared with the main PyPI proxy.
     */
    private final Slice upstream;

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Repository type (e.g. {@code "pypi"}, {@code "pypi-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for {@code /simple/<name>/} endpoints.
     */
    private final PypiMetadataRequestDetector detector;

    /**
     * HTML parser for PEP 503 Simple Index bodies.
     */
    private final PypiMetadataParser parser;

    /**
     * Pure filter that drops blocked versions.
     */
    private final PypiMetadataFilter filter;

    /**
     * Serialiser back to PEP 503 HTML.
     */
    private final PypiMetadataRewriter rewriter;

    /**
     * Shared cooldown-filtered metadata cache (WS5.5), or {@code null} when
     * cooldown metadata caching is not wired. When present, the
     * parse + cooldown-evaluation + filter + rewrite of a {@code /simple/}
     * index is paid once per (content, cutoff) rather than on every request:
     * the materialised filtered bytes are cached under the same
     * {@code metadata:{repoType}:{repoName}:{packageName}} key the shared
     * cache uses for every other format, so the existing invalidation hooks
     * (upload, proxy refresh, JDBC block/unblock envelope invalidator,
     * cross-instance pub/sub, policy-change wipe) drop stale PyPI entries too
     * without a second uncoordinated cache.
     */
    private final FilteredMetadataCache metadataCache;

    /**
     * Per-package upstream-content fingerprint (base64 SHA-256), used to
     * self-bust the filtered-index cache the instant the upstream Simple
     * Index changes. The shared cache is keyed by package name only (so its
     * invalidation hooks can match on the name), so a content change that is
     * not driven by an explicit upload/refresh hook — a proxy storage-cache
     * refresh, for instance — would otherwise keep serving a filtered view of
     * the previous bytes until the entry's TTL. Comparing the current upstream
     * fingerprint against the last one seen lets this handler invalidate the
     * shared entry itself, satisfying the WS5.5 "self-busts on content change"
     * requirement while still reusing the coordinated shared cache. Bounded so
     * it can never grow without limit.
     */
    private final Cache<String, String> contentFingerprints;

    /**
     * Max TTL for a filtered index whose versions are all past the cooldown
     * window (nothing blocked). Release/upload timestamps do not change, so a
     * long TTL is safe; content changes are handled by the fingerprint bust
     * and cooldown changes by the shared cache's invalidation hooks.
     */
    private static final Duration MAX_TTL = Duration.ofHours(24);

    /**
     * Ctor without a filtered-index cache — the filter is recomputed on every
     * request. Retained for callers (and tests) that have no cooldown metadata
     * cache to share.
     *
     * @param upstream Upstream PyPI proxy slice
     * @param cooldown Cooldown evaluation service
     * @param repoType Repository type (e.g. {@code "pypi-proxy"})
     * @param repoName Repository name
     */
    public PypiSimpleHandler(
        final Slice upstream,
        final CooldownService cooldown,
        final String repoType,
        final String repoName
    ) {
        this(upstream, cooldown, repoType, repoName, null);
    }

    /**
     * Ctor.
     *
     * @param upstream Upstream PyPI proxy slice
     * @param cooldown Cooldown evaluation service
     * @param repoType Repository type (e.g. {@code "pypi-proxy"})
     * @param repoName Repository name
     * @param metadataCache Shared cooldown-filtered metadata cache, or
     *                      {@code null} to disable filtered-index caching
     */
    public PypiSimpleHandler(
        final Slice upstream,
        final CooldownService cooldown,
        final String repoType,
        final String repoName,
        final FilteredMetadataCache metadataCache
    ) {
        this.upstream = upstream;
        this.cooldown = cooldown;
        this.repoType = repoType;
        this.repoName = repoName;
        this.detector = new PypiMetadataRequestDetector();
        this.parser = new PypiMetadataParser();
        this.filter = new PypiMetadataFilter();
        this.rewriter = new PypiMetadataRewriter();
        this.metadataCache = metadataCache;
        this.contentFingerprints = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();
    }

    /**
     * Whether this handler should intercept the given request path.
     *
     * @param path Request path
     * @return true for {@code /simple/<name>/} paths with a non-empty
     *     package name
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a Simple Index request with cooldown filtering.
     *
     * <p>The {@code clientWantsJson} flag honours the caller's PEP 691
     * content negotiation: we ALWAYS fetch JSON from upstream (the only
     * shape that carries {@code upload-time}), but we serialize back in
     * the format the original caller asked for. Without this round-trip,
     * a uv client (or any client sending {@code Accept:
     * application/vnd.pypi.simple.v1+json}) would receive HTML under a
     * JSON content-type negotiation and fall back to "no upload date".</p>
     *
     * @param line Request line (must be {@code /simple/<name>/})
     * @param clientWantsJson true if the original client requested PEP 691
     *                        JSON; false for legacy PEP 503 HTML
     * @param user Authenticated user (for cooldown bookkeeping)
     * @param headers Inbound request headers, used to capture the audit
     *                correlation context before any async hop
     * @return Future response
     */
    public CompletableFuture<Response> handle(
        final RequestLine line, final boolean clientWantsJson, final String user,
        final Headers headers
    ) {
        // Captured on the calling thread (same thread as EcsLoggingSlice) —
        // must not be re-read from MDC inside the thenCompose/thenApply
        // continuations below, which may run on a worker thread that never
        // had MDC bound.
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String path = line.uri().getPath();
        // PEP 503 normalization (lowercase + collapse runs of [-_.] to single
        // '-'): the artifact-publish path stores release dates under the
        // canonical name (see ProxySlice's NormalizedProjectName.Simple uses),
        // so the cooldown lookup must use the same form. A request for
        // /simple/Foo_Bar/ with raw name "Foo_Bar" otherwise misses the DB
        // row for "foo-bar" and the filter silently falls open ("0 blocked"),
        // leaking blocked versions to pip clients.
        final String pkg = new com.auto1.pantera.pypi.NormalizedProjectName.Simple(
            this.detector.extractPackageName(path).orElseThrow(
                () -> new IllegalArgumentException("Not a /simple/ path: " + path)
            )
        ).value();
        // RCA-pypi-A (v2.2.0): ask upstream for PEP 691 JSON instead of PEP 503
        // HTML. pypi.org's HTML response omits the data-upload-time attribute
        // we rely on; the JSON response carries upload-time on every file. The
        // proxy's content negotiation routes the upstream fetch (and the cache
        // key) through {@code SimpleApiFormat.JSON}, and ProxySlice rewrites
        // each file's url to the local /packages/ path before caching. The
        // parser detects the JSON shape and produces the same PypiSimpleIndex
        // the rewriter emits to pip as HTML.
        final Headers acceptJson = Headers.from(new Header(
            "Accept", "application/vnd.pypi.simple.v1+json"
        ));
        return this.upstream.response(line, acceptJson, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.processUpstream(bytes, pkg, clientWantsJson, user, ctx)
                );
            });
    }

    /**
     * Parse → evaluate → filter → serialise in the caller's format.
     *
     * <p>RCA-pypi-A (v2.2.0): we always fetch PEP 691 JSON upstream so we
     * have {@code upload-time} per file. The serialisation step honours
     * {@code clientWantsJson} so a uv client gets JSON back (with the
     * blocked entries filtered) and a pip client gets PEP 503 HTML.</p>
     *
     * <p>WS5.5: the expensive parse + per-version cooldown fan-out + filter +
     * rewrite is materialised into the shared {@link FilteredMetadataCache}
     * (when wired), so a second identical request for a hot package is served
     * from cache without re-evaluating cooldown. The cache self-busts on
     * content change (via {@link #bustOnContentChange}) and on cutoff advance
     * (the entry TTL is capped by the earliest {@code blockedUntil}); cooldown
     * block/unblock, uploads, proxy refreshes and cross-instance events reuse
     * the shared cache's existing invalidation hooks.</p>
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes, final String pkg,
        final boolean clientWantsJson, final String user, final AuditContext ctx
    ) {
        final PypiSimpleIndex parsed;
        try {
            parsed = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("Failed to parse upstream Simple Index — returning empty index")
                .eventCategory("web")
                .eventAction("simple_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            AuditLogger.resolution(
                ctx, this.repoType, this.repoName, pkg, user, List.of()
            );
            return CompletableFuture.completedFuture(emptyResponse(clientWantsJson, pkg));
        }
        final List<String> versions = this.parser.extractVersions(parsed);
        if (versions.isEmpty()) {
            AuditLogger.resolution(
                ctx, this.repoType, this.repoName, pkg, user, List.of()
            );
            return CompletableFuture.completedFuture(
                allowedResponse(parsed, upstreamBytes, java.util.Set.of(), clientWantsJson, pkg)
            );
        }
        final int versionCount = versions.size();
        if (this.metadataCache == null) {
            return this.computeFilteredEntry(upstreamBytes, parsed, versions, pkg, user)
                .thenApply(entry -> this.serveFromEntry(
                    entry, versionCount, clientWantsJson, pkg, user, ctx
                ));
        }
        this.bustOnContentChange(pkg, upstreamBytes);
        return this.metadataCache.getEntry(
            this.repoType, this.repoName, pkg,
            () -> this.computeFilteredEntry(upstreamBytes, parsed, versions, pkg, user)
        ).thenApply(entry -> this.serveFromEntry(
            entry, versionCount, clientWantsJson, pkg, user, ctx
        ));
    }

    /**
     * Compute the materialised filtered index for a package on a cache miss:
     * evaluate every candidate version against cooldown, collect the blocked
     * set and the earliest {@code blockedUntil}, and serialise the filtered
     * index back into the upstream's wire format (JSON when the proxy fetched
     * PEP 691 JSON — the production case — HTML otherwise). The earliest
     * {@code blockedUntil} caps the cache entry's TTL so a version that ages
     * out of the cooldown window becomes visible on the next request after
     * expiry rather than after the long no-block TTL.
     */
    private CompletableFuture<FilteredMetadataCache.CacheEntry> computeFilteredEntry(
        final byte[] upstreamBytes, final PypiSimpleIndex parsed,
        final List<String> versions, final String pkg, final String user
    ) {
        final Map<String, Instant> releaseDates = this.parser.extractReleaseDates(parsed);
        return this.blockDecisions(pkg, versions, releaseDates, user).thenApply(decisions -> {
            final Set<String> blocked = new HashSet<>();
            Instant earliest = null;
            for (final BlockDecision decision : decisions) {
                if (decision.blocked()) {
                    blocked.add(decision.version());
                    final Instant until = decision.blockedUntil();
                    if (until != null && (earliest == null || until.isBefore(earliest))) {
                        earliest = until;
                    }
                }
            }
            final byte[] materialised = this.materialise(parsed, upstreamBytes, blocked, pkg);
            if (!blocked.isEmpty()) {
                EcsLogger.info("com.auto1.pantera.pypi")
                    .message("/simple/ filtered: removed cooldown-blocked versions"
                        + " (total=" + versions.size()
                        + ", blocked=" + blocked.size() + ")")
                    .eventCategory("web")
                    .eventAction("simple_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .field("log.source", "application")
                    .log();
            }
            if (earliest != null) {
                return FilteredMetadataCache.CacheEntry.withBlockedVersions(
                    materialised, earliest, MAX_TTL, blocked
                );
            }
            return FilteredMetadataCache.CacheEntry.noBlockedVersions(materialised, MAX_TTL);
        });
    }

    /**
     * Serialise the cooldown-filtered index into the upstream's wire format.
     * Nothing blocked → the upstream bytes are cached verbatim (preserving the
     * exact byte shape the proxy produced). Otherwise JSON upstream is trimmed
     * by {@link #filterJson} (keeping {@code meta}/{@code name} intact) and
     * HTML upstream is re-rendered by the rewriter.
     */
    private byte[] materialise(
        final PypiSimpleIndex parsed, final byte[] upstreamBytes,
        final Set<String> blocked, final String pkg
    ) {
        if (blocked.isEmpty()) {
            return upstreamBytes;
        }
        if (looksLikeJson(upstreamBytes)) {
            return this.filterJson(upstreamBytes, blocked, pkg)
                .orElseGet(() -> emptyJsonBody(pkg));
        }
        final PypiSimpleIndex filtered = this.filter.filter(parsed, blocked);
        try {
            return this.rewriter.rewrite(filtered);
        } catch (final MetadataRewriteException ex) {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("/simple/ HTML rewrite failed — caching empty index")
                .eventCategory("web")
                .eventAction("simple_filter")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            return emptyHtmlBody();
        }
    }

    /**
     * Turn a (cached or freshly-computed) filtered-index entry into the
     * client-facing response, emitting exactly one {@code artifact_resolution}
     * audit record. When every upstream version is blocked the response is a
     * 404 carrying the {@code X-Pantera-Cooldown} marker; otherwise the
     * already-filtered bytes are served in the client's requested format
     * (JSON verbatim on the fast path, HTML via the rewriter).
     */
    private Response serveFromEntry(
        final FilteredMetadataCache.CacheEntry entry, final int versionCount,
        final boolean clientWantsJson, final String pkg, final String user,
        final AuditContext ctx
    ) {
        final byte[] data = entry.data();
        final Set<String> blocked = entry.blockedVersions();
        final boolean allBlocked;
        if (blocked != null) {
            AuditLogger.resolution(
                ctx, this.repoType, this.repoName, pkg, user, List.copyOf(blocked)
            );
            allBlocked = !blocked.isEmpty() && blocked.size() >= versionCount;
        } else {
            // L2 promotion loses the blocked-version detail (Valkey stores raw
            // bytes only); fall back to inspecting the materialised index.
            AuditLogger.resolutionDetailUnknown(
                ctx, this.repoType, this.repoName, pkg, user,
                "shared filtered-metadata cache"
            );
            allBlocked = this.parseOrEmpty(data).links().isEmpty();
        }
        if (allBlocked) {
            return this.allBlockedResponse(pkg);
        }
        if (clientWantsJson && looksLikeJson(data)) {
            return ResponseBuilder.ok()
                .header("Content-Type", JSON_CONTENT_TYPE)
                .body(data)
                .build();
        }
        return this.allowedResponse(
            this.parseOrEmpty(data), data, java.util.Set.of(), clientWantsJson, pkg
        );
    }

    /**
     * Invalidate the shared filtered-index entry when the upstream Simple
     * Index bytes changed since the last request for this package. The shared
     * cache is keyed by package name only, so a content change that is not
     * driven by an explicit upload/refresh hook would otherwise keep serving a
     * filtered view of the previous bytes until the entry's TTL — this
     * fingerprint comparison is what makes the WS5.5 cache "self-bust on
     * content change" while still reusing the coordinated shared cache.
     */
    private void bustOnContentChange(final String pkg, final byte[] upstreamBytes) {
        final String fingerprint = sha256(upstreamBytes);
        final String previous = this.contentFingerprints.asMap().put(pkg, fingerprint);
        if (previous != null && !previous.equals(fingerprint)) {
            this.metadataCache.invalidate(this.repoType, this.repoName, pkg);
            EcsLogger.debug("com.auto1.pantera.pypi")
                .message("Upstream Simple Index changed — dropped stale filtered entry")
                .eventCategory("web")
                .eventAction("simple_filter_invalidate")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .field("log.source", "application")
                .log();
        }
    }

    private PypiSimpleIndex parseOrEmpty(final byte[] data) {
        try {
            return this.parser.parse(data);
        } catch (final MetadataParseException ex) {
            return new PypiSimpleIndex("", List.of());
        }
    }

    /**
     * Serve when no cooldown blocks apply. If the upstream shape matches
     * the client's requested shape we can forward bytes verbatim
     * (preserving any field ordering / formatting the upstream emitted);
     * otherwise we rewrite via {@link #serialize}. In production this
     * almost always hits the JSON-in-JSON-out branch (we always request
     * JSON upstream); the HTML-in-HTML-out branch matters for tests and
     * for any future hosted-pypi index where bytes are already PEP 503.
     */
    private Response allowedResponse(
        final PypiSimpleIndex parsed, final byte[] upstreamBytes,
        final java.util.Set<String> blocked, final boolean clientWantsJson, final String pkg
    ) {
        final boolean upstreamIsJson = looksLikeJson(upstreamBytes);
        if (clientWantsJson && upstreamIsJson) {
            return ResponseBuilder.ok()
                .header("Content-Type", JSON_CONTENT_TYPE)
                .body(upstreamBytes)
                .build();
        }
        if (!clientWantsJson && !upstreamIsJson) {
            return ResponseBuilder.ok()
                .header("Content-Type", this.rewriter.contentType())
                .body(upstreamBytes)
                .build();
        }
        return serialize(parsed, upstreamBytes, blocked, clientWantsJson, pkg);
    }

    private static boolean looksLikeJson(final byte[] bytes) {
        byte first = 0;
        boolean found = false;
        for (final byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                first = b;
                found = true;
                break;
            }
        }
        return found && first == '{';
    }

    /**
     * Emit either PEP 691 JSON (filtered) or PEP 503 HTML for the given
     * (filtered) index. On rewriter failure, fall back to an empty index
     * so the client still receives a valid response.
     */
    private Response serialize(
        final PypiSimpleIndex idx, final byte[] upstreamBytes,
        final java.util.Set<String> blocked, final boolean clientWantsJson, final String pkg
    ) {
        if (clientWantsJson) {
            final java.util.Optional<byte[]> body = filterJson(upstreamBytes, blocked, pkg);
            if (body.isEmpty()) {
                return emptyResponse(true, pkg);
            }
            return ResponseBuilder.ok()
                .header("Content-Type", JSON_CONTENT_TYPE)
                .body(body.get())
                .build();
        }
        try {
            return ResponseBuilder.ok()
                .header("Content-Type", this.rewriter.contentType())
                .body(this.rewriter.rewrite(idx))
                .build();
        } catch (final MetadataRewriteException ex) {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("/simple/ HTML rewrite failed — falling back to empty index")
                .eventCategory("web")
                .eventAction("simple_filter")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            return emptyResponse(false, pkg);
        }
    }

    /**
     * Remove blocked-version entries from a PEP 691 JSON body. Keeps the
     * upstream {@code meta}, {@code name}, and any other top-level
     * fields verbatim. Returns {@link java.util.Optional#empty()} on
     * parse failure so the caller can choose an empty-index fallback.
     */
    private java.util.Optional<byte[]> filterJson(
        final byte[] upstreamBytes, final java.util.Set<String> blocked, final String pkg
    ) {
        if (blocked.isEmpty()) {
            return java.util.Optional.of(upstreamBytes);
        }
        try {
            final JsonNode root = JSON_MAPPER.readTree(upstreamBytes);
            if (!(root instanceof ObjectNode obj)) {
                return java.util.Optional.of(upstreamBytes);
            }
            final JsonNode files = obj.path("files");
            if (files instanceof ArrayNode filesArr) {
                final ArrayNode kept = JSON_MAPPER.createArrayNode();
                for (final JsonNode file : filesArr) {
                    final String filename = file.path("filename").asText("");
                    final String version = PypiMetadataParser.extractVersionFromFilename(filename);
                    if (version == null || !blocked.contains(version)) {
                        kept.add(file);
                    }
                }
                obj.set("files", kept);
            }
            final JsonNode versionsNode = obj.path("versions");
            if (versionsNode instanceof ArrayNode versionsArr) {
                final ArrayNode kept = JSON_MAPPER.createArrayNode();
                for (final JsonNode v : versionsArr) {
                    if (!blocked.contains(v.asText(""))) {
                        kept.add(v);
                    }
                }
                obj.set("versions", kept);
            }
            return java.util.Optional.of(JSON_MAPPER.writeValueAsBytes(obj));
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("/simple/ JSON rewrite failed — returning empty index")
                .eventCategory("web")
                .eventAction("simple_filter")
                .eventOutcome("failure")
                .field("repository.name", this.repoName)
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            return java.util.Optional.empty();
        }
    }

    private Response emptyResponse(final boolean clientWantsJson, final String pkg) {
        if (clientWantsJson) {
            return ResponseBuilder.ok()
                .header("Content-Type", JSON_CONTENT_TYPE)
                .body(emptyJsonBody(pkg))
                .build();
        }
        return ResponseBuilder.ok()
            .header("Content-Type", this.rewriter.contentType())
            .body(emptyHtmlBody())
            .build();
    }

    private static byte[] emptyJsonBody(final String pkg) {
        return ("{\"meta\":{\"api-version\":\"1.1\"},\"name\":\""
            + pkg + "\",\"files\":[]}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] emptyHtmlBody() {
        return "<!DOCTYPE html><html><body></body></html>"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final String JSON_CONTENT_TYPE = "application/vnd.pypi.simple.v1+json";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Every version blocked — 404. pip handles 404 on {@code /simple/}
     * as "package not found" and surfaces a clean error; returning an
     * empty HTML index would work but is more likely to produce weird
     * secondary failures in some client versions.
     */
    private Response allBlockedResponse(final String pkg) {
        EcsLogger.info("com.auto1.pantera.pypi")
            .message("/simple/ has no non-blocked versions — returning 404")
            .eventCategory("web")
            .eventAction("simple_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", pkg)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.notFound()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + pkg
                    + "' are under cooldown; no versions available."
            )
            .build();
    }

    /**
     * Evaluate every candidate against cooldown in parallel; collect
     * the blocked set. Uses {@code evaluateWithKnownDate} with the
     * per-link {@code data-upload-time} extracted by the parser so the
     * filter never has to round-trip through the {@code CooldownInspector}
     * (which would otherwise fall through to
     * {@code DbPublishDateRegistry} where no upstream
     * {@code PublishDateSource} is registered for {@code pypi}/{@code
     * pypi-proxy} and silently fail open). Mirrors the npm/composer
     * packument-inline shortcut from commit {@code dbdde1736}. Per-version
     * errors swallowed to "allowed" so a transient cooldown-service
     * hiccup never denies an entire index.
     */
    private CompletableFuture<List<BlockDecision>> blockDecisions(
        final String pkg, final List<String> candidates,
        final Map<String, Instant> releaseDates, final String user
    ) {
        final List<CompletableFuture<BlockDecision>> futures =
            new ArrayList<>(candidates.size());
        for (final String version : candidates) {
            futures.add(
                this.decide(pkg, version, releaseDates.get(version), user)
            );
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final List<BlockDecision> decisions = new ArrayList<>(candidates.size());
                for (final CompletableFuture<BlockDecision> future : futures) {
                    decisions.add(future.join());
                }
                return decisions;
            });
    }

    private CompletableFuture<BlockDecision> decide(
        final String pkg, final String version,
        final Instant releaseDate, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            pkg,
            version,
            user == null ? "pypi-simple" : user,
            Instant.now()
        );
        return this.cooldown.evaluateWithKnownDate(req, Optional.ofNullable(releaseDate))
            .thenApply(result -> {
                if (result.blocked()) {
                    final Instant until = result.block()
                        .map(block -> block.blockedUntil())
                        .orElse(null);
                    return new BlockDecision(version, true, until);
                }
                return new BlockDecision(version, false, null);
            })
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return new BlockDecision(version, false, null);
            });
    }

    /**
     * Base64 SHA-256 fingerprint of the upstream Simple Index bytes, used to
     * detect content changes so {@link #bustOnContentChange} can drop a stale
     * filtered entry.
     */
    private static String sha256(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(bytes));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Outcome of evaluating one version against cooldown: whether it is
     * blocked and, when it is, the instant the block expires (used to cap the
     * filtered-index cache TTL so an aged-out version reappears promptly).
     */
    private record BlockDecision(String version, boolean blocked, Instant blockedUntil) {
    }

    /**
     * Drain a reactive-streams body to a byte array. Mirrors the helper
     * in the Go cooldown handlers.
     */
    private static CompletableFuture<byte[]> bodyBytes(
        final org.reactivestreams.Publisher<ByteBuffer> body
    ) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
