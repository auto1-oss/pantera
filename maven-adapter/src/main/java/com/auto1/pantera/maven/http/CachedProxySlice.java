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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.BaseCachedProxySlice;
import com.auto1.pantera.http.cache.DigestComputer;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.cache.SidecarFile;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.RangeSlice;
import com.auto1.pantera.maven.asto.RepositoryChecksums;
import com.auto1.pantera.maven.cooldown.MavenMetadataFilter;
import com.auto1.pantera.maven.cooldown.MavenMetadataParser;
import com.auto1.pantera.maven.cooldown.MavenMetadataRequestDetector;
import com.auto1.pantera.maven.cooldown.MavenMetadataRewriter;
import com.auto1.pantera.maven.security.KeyringStoreRegistry;
import com.auto1.pantera.maven.security.PgpVerifier;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.reactivestreams.Publisher;

/**
 * Maven proxy slice with caching, extending unified BaseCachedProxySlice.
 *
 * <p>Maven-specific features:
 * <ul>
 *   <li>Cooldown via GAV (group/artifact/version) pattern matching</li>
 *   <li>SHA-256 + SHA-1 + MD5 digest computation</li>
 *   <li>Checksum sidecar generation (.sha1, .sha256, .md5, .sha512)</li>
 *   <li>MetadataCache for maven-metadata.xml with stale-while-revalidate</li>
 *   <li>Artifact event publishing for Maven coordinates</li>
 * </ul>
 *
 * @since 1.20.13
 */
public final class CachedProxySlice extends BaseCachedProxySlice {

    /**
     * Primary artifact extensions that participate in the coupled
     * primary+sidecar write path. The checksum sidecar paths themselves are
     * still served by {@link ChecksumProxySlice} / standard cache flow.
     */
    private static final List<String> PRIMARY_EXTENSIONS = List.of(
        ".pom", ".jar", ".war", ".aar", ".ear", ".zip", ".module"
    );

    /**
     * Maven-specific metadata cache for maven-metadata.xml files.
     */
    private final MetadataCache metadataCache;

    /**
     * Remote client slice, held here so {@link #preProcess} can fetch the
     * primary + sidecars as a coupled batch via {@link ProxyCacheWriter}.
     * A duplicate reference of {@code super.client()} is kept so we don't
     * invoke a protected getter from an anonymous fetch supplier.
     */
    private final Slice remote;

    /**
     * Optional raw storage used by {@link ProxyCacheWriter} to land the
     * primary + sidecars atomically. Empty when the upstream runs without a
     * file-backed cache; in that case we fall back to the standard flow.
     */
    private final Optional<Storage> rawStorage;

    /**
     * Single-source-of-truth cache writer introduced by WI-07 (§9.5 of the
     * v2.2 target architecture). Fetches the primary + every sidecar in one
     * coupled batch, verifies the upstream {@code .sha1}/{@code .sha256}
     * claim against the bytes we just downloaded, and atomically commits the
     * pair. Instantiated lazily when {@link #rawStorage} is present.
     */
    private final ProxyCacheWriter cacheWriter;

    /**
     * Local copy of the events queue so that {@link #enqueueEventForWriter}
     * can offer events without going through the private field in
     * {@link BaseCachedProxySlice}. Mirrors the pattern used by the Go
     * adapter for its WI-07 post-write enqueue call.
     */
    private final Optional<Queue<ProxyArtifactEvent>> localEvents;

    /**
     * Cooldown metadata filter service. When present, {@link #handleMetadata}
     * runs the upstream {@code maven-metadata.xml} bytes through the parser /
     * filter / rewriter chain before returning, so fresh versions inside the
     * admin-configured cooldown window are stripped from {@code <versions>}
     * and {@code <latest>} / {@code <release>} are re-pointed when their target
     * was blocked (an unblocked designated latest is preserved). When
     * null (legacy constructors, tests), responses pass through unfiltered —
     * same behaviour as before the metadata filter was wired.
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Per-input materialised filter cache: a stable upstream payload sha256
     * inside a 1 h bucket yields the same filtered bytes without re-running
     * the parser/filter/rewriter chain on every request. Sits ahead of
     * {@link CooldownMetadataService}'s version-keyed cache.
     */
    private final PerInputFilteredMetadataCache materialisedCache =
        new PerInputFilteredMetadataCache();

    /**
     * WS4-maven.1/.2: per-repo {@code verifyPgp} flag. When {@code false}
     * (default, byte-identical to pre-2.3.0 behaviour) {@link #fetchSidecar}
     * is never asked for {@code .asc} and {@link KeyringStoreRegistry} is
     * never consulted — no keyring lookups occur at all.
     */
    private final boolean verifyPgp;

    /**
     * Constructor with full configuration (no metadata filtering).
     * Delegates to the overload below with {@code cooldownMetadata=null}; used
     * by legacy callers and tests that do not need filter behaviour.
     * @param client Upstream remote slice
     * @param cache Asto cache for artifact storage
     * @param events Event queue for proxy artifact events
     * @param repoName Repository name
     * @param upstreamUrl Upstream base URL
     * @param repoType Repository type
     * @param cooldownService Cooldown service
     * @param cooldownInspector Cooldown inspector
     * @param storage Optional local storage
     * @param config Unified proxy cache configuration
     * @param metadataCache Maven metadata cache
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String repoName,
        final String upstreamUrl,
        final String repoType,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector,
        final Optional<Storage> storage,
        final ProxyCacheConfig config,
        final MetadataCache metadataCache
    ) {
        this(
            client, cache, events, repoName, upstreamUrl, repoType,
            cooldownService, cooldownInspector, storage, config, metadataCache,
            null
        );
    }

    /**
     * Constructor with metadata filter enabled.
     * @param client Upstream remote slice
     * @param cache Asto cache for artifact storage
     * @param events Event queue for proxy artifact events
     * @param repoName Repository name
     * @param upstreamUrl Upstream base URL
     * @param repoType Repository type
     * @param cooldownService Cooldown service
     * @param cooldownInspector Cooldown inspector
     * @param storage Optional local storage
     * @param config Unified proxy cache configuration
     * @param metadataCache Maven metadata cache
     * @param cooldownMetadata Cooldown metadata filter service, or null to
     *                         disable filtering on this slice
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String repoName,
        final String upstreamUrl,
        final String repoType,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector,
        final Optional<Storage> storage,
        final ProxyCacheConfig config,
        final MetadataCache metadataCache,
        final CooldownMetadataService cooldownMetadata
    ) {
        this(
            client, cache, events, repoName, upstreamUrl, repoType,
            cooldownService, cooldownInspector, storage, config, metadataCache,
            cooldownMetadata, false
        );
    }

    /**
     * Constructor with metadata filter AND PGP verification (WS4-maven.1/.2).
     * The single field-initializing constructor — every other overload
     * delegates here.
     * @param client Upstream remote slice
     * @param cache Asto cache for artifact storage
     * @param events Event queue for proxy artifact events
     * @param repoName Repository name
     * @param upstreamUrl Upstream base URL
     * @param repoType Repository type
     * @param cooldownService Cooldown service
     * @param cooldownInspector Cooldown inspector
     * @param storage Optional local storage
     * @param config Unified proxy cache configuration
     * @param metadataCache Maven metadata cache
     * @param cooldownMetadata Cooldown metadata filter service, or null to
     *                         disable filtering on this slice
     * @param verifyPgp Whether to verify {@code .asc} signatures against the
     *                  admin-managed keyring before committing a fetched
     *                  primary to cache
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String repoName,
        final String upstreamUrl,
        final String repoType,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector,
        final Optional<Storage> storage,
        final ProxyCacheConfig config,
        final MetadataCache metadataCache,
        final CooldownMetadataService cooldownMetadata,
        final boolean verifyPgp
    ) {
        super(
            client, cache, repoName, repoType, upstreamUrl,
            storage, events, config, cooldownService, cooldownInspector
        );
        this.metadataCache = metadataCache;
        this.remote = client;
        this.rawStorage = storage;
        this.localEvents = events;
        // Always-verify (Track 3): a Maven proxy without raw storage cannot
        // run the upstream-sha1 verification path, which means primary bytes
        // would land in the cache unverified. Refuse to construct rather
        // than silently fall back — the YAML wiring should guarantee
        // storage is present, and a misconfiguration must fail loudly at
        // startup, not corrupt cache state at the first request.
        this.cacheWriter = new ProxyCacheWriter(
            storage.orElseThrow(() -> new IllegalArgumentException(
                "Maven CachedProxySlice requires raw storage for upstream-sha1 "
                + "verification; repository '" + repoName + "' was constructed "
                + "with Optional.empty() — check the proxy YAML configuration."
            )),
            repoName
        );
        this.cooldownMetadata = cooldownMetadata;
        this.verifyPgp = verifyPgp;
    }

    /**
     * Backward-compatible constructor (uses defaults for config and no metadata cache).
     * @param client Upstream remote slice
     * @param cache Asto cache for artifact storage
     * @param events Event queue for proxy artifact events
     * @param repoName Repository name
     * @param upstreamUrl Upstream base URL
     * @param repoType Repository type
     * @param cooldownService Cooldown service
     * @param cooldownInspector Cooldown inspector
     * @param storage Optional local storage
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String repoName,
        final String upstreamUrl,
        final String repoType,
        final CooldownService cooldownService,
        final CooldownInspector cooldownInspector,
        final Optional<Storage> storage
    ) {
        this(
            client, cache, events, repoName, upstreamUrl, repoType,
            cooldownService, cooldownInspector, storage,
            ProxyCacheConfig.defaults(), null
        );
    }

    @Override
    protected boolean isCacheable(final String path) {
        // Don't cache directories
        return !isDirectory(path);
    }

    @Override
    protected Optional<CompletableFuture<Response>> preProcess(
        final RequestLine line, final Headers headers, final Key key, final String path
    ) {
        // maven-metadata.xml uses dedicated MetadataCache with stale-while-revalidate
        if (path.contains("maven-metadata.xml") && this.metadataCache != null) {
            return Optional.of(this.handleMetadata(line, headers, key));
        }
        // WI-07 §9.5 — integrity-verified atomic primary+sidecar write on
        // cache-miss. cacheWriter is non-null by construction (constructor
        // throws on empty storage as of Track 3), so primaries always
        // route through the verification path. Cache-hit and sidecar paths
        // fall through to the standard BaseCachedProxySlice flow unchanged.
        // Track 5 Phase 1A: cooldown evaluation moved INSIDE
        // verifyAndServePrimary so it only runs on cache-miss. A cache hit
        // serves from local storage with zero upstream I/O — no HEAD to
        // MavenHeadSource, no inspector network fallback. The trade-off:
        // a cooldown rule applied AFTER an artifact was first cached only
        // takes effect on the next miss; the admin's tool for blocking an
        // already-cached version is cache eviction.
        if (!isChecksumSidecar(path) && isPrimaryArtifact(path)) {
            // WS4-maven.11: wrap the whole primary-serve pipeline in
            // RangeSlice so a Range request is honoured whichever branch
            // (cache hit / cache().load() hit / fresh-fetch commit)
            // produces the 200 — RangeSlice only needs Content-Length on
            // that response's headers (added in each branch below) and a
            // no-Range request passes straight through unmodified, so this
            // is a no-op when the client never asks for a range.
            return Optional.of(
                new RangeSlice(
                    (rangeLine, rangeHeaders, rangeBody) ->
                        this.verifyAndServePrimary(line, headers, key, path)
                ).response(line, headers, Content.EMPTY)
            );
        }
        return Optional.empty();
    }

    @Override
    protected Optional<CooldownRequest> buildCooldownRequest(
        final String path, final Headers headers
    ) {
        // Strip leading '/' for pattern matching (Key format has no leading slash)
        final String keyPath = path.startsWith("/") ? path.substring(1) : path;
        final Matcher matcher = MavenSlice.ARTIFACT.matcher(keyPath);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String pkg = matcher.group("pkg");
        final int idx = pkg.lastIndexOf('/');
        if (idx < 0 || idx == pkg.length() - 1) {
            return Optional.empty();
        }
        final String dirVersion = pkg.substring(idx + 1);
        final String artifact = MavenSlice.EVENT_INFO.formatArtifactName(
            pkg.substring(0, idx)
        );
        // SNAPSHOT timestamped artifacts (e.g. lib-1.0-20260519.090000-1.jar)
        // live under a SNAPSHOT directory but each upload has a distinct
        // version stamp. Use the timestamp form as the cooldown version so
        // admission gates and DB rows differentiate uploads — falling back to
        // the directory name for release artifacts and non-timestamped
        // SNAPSHOTs (lib-1.0-SNAPSHOT.jar).
        final String version = extractSnapshotVersion(keyPath).orElse(dirVersion);
        final String user = new Login(headers).getValue();
        return Optional.of(
            new CooldownRequest(
                this.repoType(),
                this.repoName(),
                artifact,
                version,
                user,
                Instant.now()
            )
        );
    }

    /**
     * Maven SNAPSHOT timestamp pattern: matches an artifact basename of the
     * form {@code <artifactId>-<base>-<yyyyMMdd.HHmmss>-<buildNumber>[-<classifier>].<ext>}.
     * Capture group 1 isolates the {@code <base>} stem (e.g. {@code 1.0}),
     * group 2 isolates the timestamped portion. The two groups are joined to
     * form the canonical timestamped version used by Maven Resolver.
     * Released artifacts and non-timestamped SNAPSHOTs do not match.
     */
    private static final Pattern SNAPSHOT_TIMESTAMP = Pattern.compile(
        "^[^/]+?-([^/]+)-(\\d{8}\\.\\d{6}-\\d+)(?:-[^.]+)?\\.[^.]+$"
    );

    /**
     * Extract the timestamped SNAPSHOT version from a Maven artifact path.
     * Combines the base version stem with the {@code yyyyMMdd.HHmmss-N}
     * suffix from the basename — e.g. {@code lib-1.0-20260519.090000-1.jar}
     * yields {@code 1.0-20260519.090000-1}. Classifier suffixes (
     * {@code -sources}, {@code -javadoc}, {@code -tests}) are accommodated.
     *
     * @param path Request path (no leading slash)
     * @return Combined {@code base-timestamp-build} cooldown version, or empty
     */
    static Optional<String> extractSnapshotVersion(final String path) {
        final int slash = path.lastIndexOf('/');
        final String basename = slash >= 0 ? path.substring(slash + 1) : path;
        final Matcher m = SNAPSHOT_TIMESTAMP.matcher(basename);
        if (m.matches()) {
            return Optional.of(m.group(1) + "-" + m.group(2));
        }
        return Optional.empty();
    }

    @Override
    protected java.util.Set<String> digestAlgorithms() {
        return DigestComputer.MAVEN_DIGESTS;
    }

    @Override
    protected Optional<ProxyArtifactEvent> buildArtifactEvent(
        final Key key, final Headers responseHeaders, final long size,
        final String owner
    ) {
        final Matcher matcher = MavenSlice.ARTIFACT.matcher(key.string());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        // Track 5 Phase 3B: consult the PublishDateExtractors SPI first, so
        // the per-repo-type registration in VertxMain is the single source
        // of truth. Fall back to the in-class extractLastModified helper
        // when no extractor is registered (NO_OP returns empty) — keeps
        // pre-Track-5 behaviour for boot paths that haven't wired the
        // registry yet.
        final Optional<Long> lastModified = com.auto1.pantera.publishdate
            .PublishDateExtractors.instance()
            .forRepoType(this.repoType())
            .extract(responseHeaders, matcher.group("pkg"), "")
            .map(java.time.Instant::toEpochMilli)
            .or(() -> extractLastModified(responseHeaders));
        return Optional.of(
            new ProxyArtifactEvent(
                new Key.From(matcher.group("pkg")),
                this.repoName(),
                this.repoType(),
                owner,
                lastModified
            )
        );
    }

    @Override
    protected List<SidecarFile> generateSidecars(
        final String path, final Map<String, String> digests
    ) {
        if (digests.isEmpty()) {
            return Collections.emptyList();
        }
        final List<SidecarFile> sidecars = new ArrayList<>(4);
        addSidecar(sidecars, path, digests, DigestComputer.SHA256, ".sha256");
        addSidecar(sidecars, path, digests, DigestComputer.SHA1, ".sha1");
        addSidecar(sidecars, path, digests, DigestComputer.MD5, ".md5");
        addSidecar(sidecars, path, digests, DigestComputer.SHA512, ".sha512");
        return sidecars;
    }

    @Override
    protected boolean isChecksumSidecar(final String path) {
        return path.endsWith(".md5") || path.endsWith(".sha1")
            || path.endsWith(".sha256") || path.endsWith(".sha512")
            || path.endsWith(".asc") || path.endsWith(".sig");
    }

    /**
     * Handle maven-metadata.xml requests using dedicated MetadataCache.
     *
     * <p>When {@link #cooldownMetadata} is non-null, the cached upstream XML
     * is run through the parser / filter / rewriter chain before the
     * response is built — fresh versions inside the configured cooldown
     * window are stripped from {@code <versions>}; a blocked {@code <latest>} /
     * {@code <release>} is re-pointed to the newest surviving version (an
     * unblocked designated latest is preserved).
     * The metadata cache itself stores UNFILTERED upstream bytes so the
     * filter decision re-evaluates per request (cooldown state changes as
     * versions age out of the window — caching filtered output would
     * produce stale decisions).</p>
     *
     * <p>If every version is blocked, returns 403 with a short explanation.
     * On any filter error (parse failure, unexpected upstream format) the
     * unfiltered bytes are served instead — availability over strictness,
     * matching the npm adapter's fail-open behaviour.</p>
     *
     * @param line Request line
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> handleMetadata(
        final RequestLine line, final Headers inboundHeaders, final Key key
    ) {
        final CompletableFuture<Optional<Content>> loaded = this.metadataCache.load(
            key,
            request -> this.fetchMetadata(line, request)
        );
        return loaded.thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            if (this.cooldownMetadata == null) {
                // No metadata-filter service wired — still a metadata
                // listing view; audit with nothing filtered.
                final com.auto1.pantera.audit.AuditContext metaCtx =
                    this.captureAuditContext(inboundHeaders);
                final String pkg = new MavenMetadataRequestDetector()
                    .extractPackageName(line.uri().getPath())
                    .map(name -> name.replace('/', '.'))
                    .orElseGet(() -> line.uri().getPath());
                com.auto1.pantera.audit.AuditLogger.resolution(
                    metaCtx, this.repoType(), this.repoName(), pkg,
                    new Login(inboundHeaders).getValue(), java.util.List.of()
                );
                return opt.get().asBytesFuture().thenApply(
                    bytes -> buildMetadataResponse(inboundHeaders, bytes)
                );
            }
            return this.applyMetadataCooldown(line, inboundHeaders, opt.get());
        });
    }

    /**
     * Conditional upstream fetch for {@code maven-metadata.xml}. Sends
     * {@code If-None-Match} / {@code If-Modified-Since} when the cache has
     * matching validators; maps the upstream status to the corresponding
     * {@link MetadataCache.MetadataFetchResult} variant:
     *
     * <ul>
     *   <li>200 OK → {@code modified} with the new bytes + upstream validators.</li>
     *   <li>304 Not Modified → {@code unmodified} (no blob rewrite).</li>
     *   <li>404 Not Found → {@code notFound} (clears the cache entry).</li>
     *   <li>Any other status → {@code notFound} so the cache surfaces the
     *       absence to the slice (cache poisoning prevention — we never
     *       commit upstream 5xx bytes to the metadata cache).</li>
     * </ul>
     *
     * @param line Inbound request line, reused for the upstream call.
     * @param request Cached validators (may be empty on cold miss).
     * @return Future with the {@link MetadataCache.MetadataFetchResult}.
     */
    private CompletableFuture<MetadataCache.MetadataFetchResult> fetchMetadata(
        final RequestLine line,
        final MetadataCache.ConditionalRequest request
    ) {
        final Headers upstreamHeaders = new Headers();
        request.etag().ifPresent(v -> upstreamHeaders.add("If-None-Match", v));
        request.lastModified().ifPresent(v -> upstreamHeaders.add("If-Modified-Since", v));
        return this.client().response(line, upstreamHeaders, Content.EMPTY)
            .thenCompose(resp -> {
                final int status = resp.status().code();
                if (status == com.auto1.pantera.http.RsStatus.NOT_MODIFIED.code()) {
                    // Drain body to release connection — 304 typically has
                    // empty body but be defensive.
                    return resp.body().asBytesFuture().thenApply(
                        ignored -> MetadataCache.MetadataFetchResult.unmodified()
                    );
                }
                if (resp.status().success()) {
                    final String etag = headerValue(resp.headers(), "ETag");
                    final String lastModified = headerValue(resp.headers(), "Last-Modified");
                    return resp.body().asBytesFuture().thenApply(
                        bytes -> MetadataCache.MetadataFetchResult.modified(
                            bytes, etag, lastModified
                        )
                    );
                }
                // 404 or any other non-success — clear the cache entry so a
                // subsequent fetch goes back to upstream cold. We never store
                // upstream errors as cache content.
                return resp.body().asBytesFuture().thenApply(
                    ignored -> MetadataCache.MetadataFetchResult.notFound()
                );
            });
    }

    private static String headerValue(final Headers headers, final String name) {
        final List<String> values = headers.values(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Run upstream {@code maven-metadata.xml} bytes through the cooldown
     * metadata filter. Extracts the package coordinate (groupId/artifactId)
     * from the URL path, drains the reactive body, invokes the filter
     * service, and wraps the filtered bytes in a 200 response. Falls through
     * to the unfiltered bytes on any non-{@link AllVersionsBlockedException}
     * failure so upstream quirks do not turn metadata requests into 5xx.
     */
    private CompletableFuture<Response> applyMetadataCooldown(
        final RequestLine line, final Headers inboundHeaders, final Content content
    ) {
        // Captured before any async hop below so the audit record threaded
        // into MetadataFilterService reflects THIS request's correlation
        // context, not whatever (or nothing) is bound to the worker thread
        // that eventually runs the .thenCompose continuation.
        com.auto1.pantera.http.log.RequestContextHeaders.bindToMdc(inboundHeaders);
        final com.auto1.pantera.audit.AuditContext auditCtx = new com.auto1.pantera.audit.AuditContext(
            org.slf4j.MDC.get(com.auto1.pantera.http.log.EcsMdc.TRACE_ID),
            org.slf4j.MDC.get(com.auto1.pantera.http.log.EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(inboundHeaders).getValue();
        final String path = line.uri().getPath();
        final Optional<String> pkgOpt = new MavenMetadataRequestDetector()
            .extractPackageName(path);
        if (pkgOpt.isEmpty()) {
            // Path didn't parse as a package coordinate — the metadata is
            // still served (unfiltered), so audit the view; the filter
            // never ran, hence detail-unknown.
            com.auto1.pantera.audit.AuditLogger.resolutionDetailUnknown(
                auditCtx, this.repoType(), this.repoName(), path, owner,
                "unparseable metadata coordinate (unfiltered passthrough)"
            );
            return content.asBytesFuture().thenApply(
                bytes -> buildMetadataResponse(inboundHeaders, bytes)
            );
        }
        // extractPackageName returns SLASHED format (com/google/guava/guava)
        // — the MavenHeadSource that resolves release dates splits on the last
        // DOT to derive groupId/artifactId, so a slashed name silently produces
        // an empty inspector lookup and the filter fails open ("0 blocked"
        // even when the version is well past its publish-date window). Convert
        // to dotted before handing it to the metadata service. Mirrors the
        // same conversion applied in MavenGroupSlice.applyCooldownFilter.
        final boolean snapshot = isSnapshotMetadataPath(path);
        final String packageName = pkgOpt.get().replace('/', '.');
        final com.auto1.pantera.cooldown.metadata.MetadataParser<org.w3c.dom.Document> parser;
        final com.auto1.pantera.cooldown.metadata.MetadataFilter<org.w3c.dom.Document> filter;
        final com.auto1.pantera.cooldown.metadata.MetadataRewriter<org.w3c.dom.Document> rewriter;
        if (snapshot) {
            // Resolve the SNAPSHOT bundle via the global registry so the
            // CooldownWiring registrations are the single source of truth.
            // Falls back to direct instantiation if the bundle is absent
            // (e.g. an embedded test that skipped CooldownWiring boot).
            final java.util.Optional<com.auto1.pantera.cooldown.config.CooldownAdapterBundle<?>>
                snapBundle = com.auto1.pantera.cooldown.config.CooldownAdapterRegistry
                    .instance().get(this.repoType() + "-snapshot");
            if (snapBundle.isPresent()) {
                @SuppressWarnings("unchecked")
                final com.auto1.pantera.cooldown.config.CooldownAdapterBundle<org.w3c.dom.Document>
                    typed = (com.auto1.pantera.cooldown.config.CooldownAdapterBundle<org.w3c.dom.Document>)
                        snapBundle.get();
                parser = typed.parser();
                filter = typed.filter();
                rewriter = typed.rewriter();
            } else {
                parser = new com.auto1.pantera.maven.cooldown.MavenSnapshotMetadataParser();
                filter = new com.auto1.pantera.maven.cooldown.MavenSnapshotMetadataFilter();
                rewriter = new com.auto1.pantera.maven.cooldown.MavenSnapshotMetadataRewriter();
            }
        } else {
            parser = new MavenMetadataParser();
            filter = new MavenMetadataFilter();
            rewriter = new MavenMetadataRewriter();
        }
        return content.asBytesFuture().thenCompose(bytes -> {
            final String sha = PerInputFilteredMetadataCache.sha256(bytes);
            final Optional<byte[]> cached = this.materialisedCache.get(
                this.repoType(), this.repoName(), packageName, sha
            );
            if (cached.isPresent()) {
                // Materialised filtered-output cache hit: the listing view is
                // still served to THIS requester — audit it. The cache holds
                // bytes only, so the filtered-version detail is unknown here.
                com.auto1.pantera.audit.AuditLogger.resolutionDetailUnknown(
                    auditCtx, this.repoType(), this.repoName(), packageName,
                    owner, "materialised filtered-metadata cache"
                );
                return CompletableFuture.completedFuture(
                    buildMetadataResponse(inboundHeaders, cached.get())
                );
            }
            return this.cooldownMetadata.filterMetadata(
                this.repoType(),
                this.repoName(),
                packageName,
                bytes,
                parser,
                filter,
                rewriter,
                auditCtx,
                owner
            ).handle((filtered, ex) -> {
                if (ex == null) {
                    this.materialisedCache.put(
                        this.repoType(), this.repoName(), packageName, sha, filtered
                    );
                    return buildMetadataResponse(inboundHeaders, filtered);
                }
                Throwable cause = ex;
                while (cause != null) {
                    if (cause instanceof AllVersionsBlockedException) {
                        EcsLogger.info("com.auto1.pantera.maven")
                            .message("All versions blocked by cooldown")
                            .eventCategory("database")
                            .eventAction("all_versions_blocked")
                            .field("repository.name", this.repoName())
                            .field("package.name", packageName)
                            .field("log.source", "application")
                            .log();
                        return ResponseBuilder.forbidden()
                            .textBody(
                                "All versions of '" + packageName
                                    + "' are under cooldown; no non-blocked "
                                    + "version is currently available."
                            )
                            .build();
                    }
                    cause = cause.getCause();
                }
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Cooldown metadata filter failed — serving unfiltered")
                    .eventCategory("database")
                    .eventAction("filter_error")
                    .field("repository.name", this.repoName())
                    .field("package.name", packageName)
                    .error(ex)
                    .field("log.source", "application")
                    .log();
                return buildMetadataResponse(inboundHeaders, bytes);
            });
        });
    }

    /**
     * Distinguish artifact-level metadata ({@code .../my-lib/maven-metadata.xml})
     * from snapshot-level metadata ({@code .../my-lib/1.0-SNAPSHOT/maven-metadata.xml}).
     * Snapshot-level path has a {@code -SNAPSHOT} segment immediately before
     * the filename.
     */
    private static boolean isSnapshotMetadataPath(final String path) {
        if (path == null) {
            return false;
        }
        final int suffix = path.lastIndexOf("/maven-metadata.xml");
        if (suffix <= 0) {
            return false;
        }
        final String parent = path.substring(0, suffix);
        final int lastSlash = parent.lastIndexOf('/');
        final String dir = lastSlash >= 0 ? parent.substring(lastSlash + 1) : parent;
        return dir.endsWith("-SNAPSHOT");
    }

    /**
     * Build the canonical Pantera-owned metadata response: explicit
     * Content-Type, Pantera-computed ETag (SHA-256 of the served bytes), and
     * a current Last-Modified. Strips all upstream validators (CF-Cache-Status,
     * X-Amz-*, X-Checksum-*, Age, etc.) so the response advertises only what
     * Pantera vouches for. If the inbound request carries a matching
     * {@code If-None-Match}, return 304 with no body.
     *
     * @param inboundHeaders Client request headers (for conditional GET match)
     * @param bytes Filtered (or unfiltered) response body
     * @return 200 OK with body OR 304 Not Modified
     */
    static Response buildMetadataResponse(
        final Headers inboundHeaders, final byte[] bytes
    ) {
        final String etag = weakEtag(bytes);
        final String lastModified = httpDate(Instant.now());
        if (etag.equals(firstHeader(inboundHeaders, "If-None-Match"))) {
            return ResponseBuilder.from(com.auto1.pantera.http.RsStatus.NOT_MODIFIED)
                .header("ETag", etag)
                .header("Last-Modified", lastModified)
                .build();
        }
        return ResponseBuilder.ok()
            .header("Content-Type", "application/xml; charset=utf-8")
            .header("ETag", etag)
            .header("Last-Modified", lastModified)
            .body(bytes)
            .build();
    }

    private static String weakEtag(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(bytes);
            return "W/\"" + Base64.getEncoder().encodeToString(hash) + "\"";
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String httpDate(final Instant when) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(when, ZoneOffset.UTC)
        );
    }

    private static String firstHeader(final Headers headers, final String name) {
        if (headers == null) {
            return null;
        }
        final List<String> values = headers.values(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Check if path represents a directory (not a file).
     * @param path Request path
     * @return True if path looks like a directory
     */
    private static boolean isDirectory(final String path) {
        if (path.endsWith("/")) {
            return true;
        }
        final int slash = path.lastIndexOf('/');
        final String segment = slash >= 0 ? path.substring(slash + 1) : path;
        return !segment.contains(".");
    }

    /**
     * Add a sidecar file to the list if the digest for the algorithm exists.
     * @param sidecars List to add to
     * @param path Original artifact path
     * @param digests Computed digests map
     * @param algorithm Digest algorithm name
     * @param extension Sidecar file extension (e.g., ".sha256")
     */
    private static void addSidecar(
        final List<SidecarFile> sidecars,
        final String path,
        final Map<String, String> digests,
        final String algorithm,
        final String extension
    ) {
        final String digest = digests.get(algorithm);
        if (digest != null) {
            // Strip leading '/' for storage key if present
            final String sidecarPath = path.startsWith("/")
                ? path.substring(1) + extension
                : path + extension;
            sidecars.add(new SidecarFile(
                sidecarPath,
                digest.getBytes(StandardCharsets.UTF_8)
            ));
        }
    }

    // ===== WI-07 §9.5: ProxyCacheWriter integration =====

    /**
     * Check if a path represents a Maven primary artifact that benefits from
     * coupled primary+sidecar writing. Metadata files, directories and
     * checksum sidecars are explicitly excluded by callers.
     *
     * @param path Request path.
     * @return {@code true} if we should route this request through
     *         {@link ProxyCacheWriter}.
     */
    private static boolean isPrimaryArtifact(final String path) {
        if (path.endsWith("/") || path.contains("maven-metadata.xml")) {
            return false;
        }
        final String lower = path.toLowerCase(Locale.ROOT);
        for (final String ext : PRIMARY_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Primary-artifact flow: if the cache already has the primary, serve it
     * with **zero upstream I/O** (no cooldown HEAD, no inspector network
     * fallback). Otherwise gate via cooldown — only on the about-to-go-upstream
     * branch — and fetch + verify + commit on allow.
     *
     * <p>Track 5 Phase 1A inversion: pre-Track 5 the cooldown gate wrapped
     * BOTH branches via the old {@code verifyAndServePrimaryGated}, which
     * forced a Maven Central HEAD on every cached request through the
     * {@link com.auto1.pantera.publishdate.RegistryBackedInspector} chain
     * (the inspector falls through to {@code MavenHeadSource} on L1+L2
     * miss). That made cached artifact serving dependent on Maven Central
     * being reachable AND inside its rate-limit budget. Now: cache-hit is
     * pure-local; cache-miss runs the gate exactly where the upstream call
     * is unavoidable anyway.
     *
     * <p>We consult BOTH the {@link Storage} and the {@link Cache} abstraction
     * so tests that plug a lambda-Cache without a real storage keep working,
     * and production file-backed deployments benefit from the verify path on
     * genuine cache misses.
     */
    private CompletableFuture<Response> verifyAndServePrimary(
        final RequestLine line, final Headers headers, final Key key, final String path
    ) {
        // Captured before any async hop below so the access-audit record
        // reflects THIS request's correlation context.
        final com.auto1.pantera.audit.AuditContext auditCtx = this.captureAuditContext(headers);
        final Storage storage = this.rawStorage.orElseThrow();
        return storage.exists(key).thenCompose(presentInStorage -> {
            if (presentInStorage) {
                return this.serveFromCache(storage, key, auditCtx, path, headers);
            }
            // Cooldown gate moved into fetchVerifyAndCache (post-headers).
            // The pre-fetch HEAD probe via MavenHeadSource was the dominant
            // cold-start cost (53s / 77s gap on a 1579-coord cold bench,
            // 2026-06-29). We now read Last-Modified from the upstream GET
            // response itself, halving upstream RPS per cold artifact and
            // recovering the gap; the verdict still fires before any cache
            // commit, so blocked artifacts never land in storage.
            return this.cache().load(
                key,
                com.auto1.pantera.asto.cache.Remote.EMPTY,
                com.auto1.pantera.asto.cache.CacheControl.Standard.ALWAYS
            ).thenCompose(opt -> {
                if (opt.isPresent()) {
                    return this.serveArtifactWithHeaders(
                        storage, key, headers, () -> CompletableFuture.completedFuture(opt.get())
                    ).thenApply(resp -> {
                        this.auditPrimaryAccess(
                            auditCtx, path, headers, contentLength(resp),
                            com.auto1.pantera.audit.AuditLogger.OUTCOME_SUCCESS, null
                        );
                        return resp;
                    });
                }
                // M4 (analysis/plan/v1/PLAN.md): concurrent clients for the
                // same uncached primary must collapse to one upstream call.
                // Pre-M4 each request fired its own fetchVerifyAndCache,
                // multiplying outbound by N for a burst of N — the
                // dominant cold-walk amplifier after M2's prefetch deletion.
                // The follower path re-runs verifyAndServePrimary which
                // hits the warm cache the leader wrote — but only AFTER
                // the leader's verificationOutcome fires (cache commit
                // complete), not when the leader's response future
                // resolves (which happens before the body is drained
                // through the stream-through tee).
                return this.coalesceUpstream(
                    key,
                    leaderGate -> this.fetchVerifyAndCache(line, headers, key, path, leaderGate, auditCtx),
                    () -> this.verifyAndServePrimary(line, headers, key, path)
                );
            }).toCompletableFuture();
        }).exceptionally(err -> {
            // RCA-NC1 (v2.2.0): MUST surface as bad-gateway, never 404. The
            // GroupResolver writes the response status into the shared
            // negative cache when querySequentially's terminal status is
            // 404, so returning notFound() here would lock the artifact
            // out of every group lookup for the cache TTL — even though
            // upstream serves it fine. M5 fixed the same hazard for
            // categorised upstream non-2xx (mapUpstreamStatus); this is
            // the uncategorised exception path (storage faults, cooldown
            // service errors, single-flight gate aborts, H2 read idle
            // timeouts after the abort) which M5 missed.
            EcsLogger.warn("com.auto1.pantera.maven.http")
                .message("Primary-artifact verify-and-serve failed; surfacing 502 so group does not poison the negative cache")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("repository.name", this.repoName())
                .field("url.path", path)
                .error(err)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.badGateway()
                .textBody("Upstream temporarily unavailable")
                .build();
        });
    }

    /**
     * Track 4 stream-through cache write: tee the upstream body to the
     * client AND to a verifying temp file in a single pass. The client
     * receives the first byte as soon as upstream emits it; verification
     * against the upstream {@code .sha1} runs on stream completion and
     * decides whether the temp file gets committed to the cache (Track 3's
     * sidecar-first atomic order) or dropped with an integrity_failure
     * metric.
     *
     * <p>Compared to the pre-Track-4 {@code writeAndVerify} flow, the
     * client no longer waits for the entire body to drain into a temp
     * file + the {@code .sha1} round-trip before its first byte arrives.
     * On a cold {@code mvn dependency:resolve} this halves the wall clock
     * for primaries large enough that disk-write dominated serve latency.
     *
     * <p>Trade-off: a {@code .sha1} mismatch means the client received
     * unverified bytes (Maven's own client-side checksum policy is the
     * final gate — same semantics as Nexus/JFrog stream-through). The
     * <i>cache</i> still upholds Track 3's always-verify invariant: a
     * mismatched primary is never persisted, and the next request
     * re-fetches cleanly from upstream.
     */
    private CompletableFuture<Response> fetchVerifyAndCache(
        final RequestLine line, final Headers inboundHeaders,
        final Key key, final String path,
        final CompletableFuture<Void> singleFlightGate,
        final com.auto1.pantera.audit.AuditContext auditCtx
    ) {
        this.rawStorage.orElseThrow(); // guard: storage must be configured
        final String upstreamUri = this.upstreamUrl() + path;
        final RequestContext ctx = new RequestContext(
            org.apache.logging.log4j.ThreadContext.get("trace.id"),
            null,
            this.repoName(),
            path
        );
        // Phase 7 perf (2026-05): only .sha1 is eagerly fetched alongside the
        // primary. mvn does NOT request .md5/.sha256/.sha512 for resolution —
        // eagerly fetching those 3 extra sidecars per primary inflated upstream
        // amplification to ~3.6×, contended with primary downloads, and
        // serialised the foreground walk through the upstream H2 pool.
        // If a client explicitly requests .md5/.sha256/.sha512, the request
        // falls through the standard cache-miss path and is proxied on demand
        // (same behaviour as Maven Central).
        // PERF (2026-05): fire the .sha1 sidecar fetch IMMEDIATELY, in
        // parallel with the primary request — not lazily inside
        // streamThroughAndCommit (which only invokes the Supplier AFTER
        // fetchPrimaryBody resolves, serialising the two round-trips and
        // doubling the per-request critical-path latency).
        //
        // Both requests now run concurrently against the same Jetty H2
        // upstream connection (separate streams). The Supplier we store
        // captures the already-running future, so when
        // streamThroughAndCommit calls .get() it joins instead of
        // refiring. Behaviour-equivalent on the verify path: the writer
        // still waits for sidecar completion before committing.
        //
        // Measured impact on cold-bench: per-request critical path drops
        // from primary-RTT + sidecar-RTT (~140 ms) to max(primary, sidecar)
        // (~70 ms), cutting upstream amplification's wall-time cost in
        // half on the dominant pom-walk path.
        final CompletionStage<Optional<InputStream>> sha1Inflight =
            this.fetchSidecar(line, inboundHeaders, ".sha1");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, () -> sha1Inflight);
        // WS4-maven.2: fetch `.asc` in parallel with the primary + sha1 —
        // ONLY when verifyPgp is enabled for this repo, so a disabled repo
        // never issues the extra upstream call (byte-identical to pre-2.3.0
        // when off). Verified AFTER commit, against the bytes storage just
        // wrote — see the PGP note on the Result.Ok branch below for the
        // accepted streaming trade-off this mirrors from the existing sha1
        // verify-after-stream design.
        final Optional<CompletionStage<Optional<InputStream>>> ascInflight = this.verifyPgp
            ? Optional.of(this.fetchSidecar(line, inboundHeaders, ".asc"))
            : Optional.empty();
        return this.fetchPrimaryBody(line, inboundHeaders).toCompletableFuture().thenCompose(body ->
            this.cooldownAtHeaders(inboundHeaders, path, body).thenCompose(blockResp -> {
                if (blockResp.isPresent()) {
                    // Block decided at header time. Drain the upstream body so
                    // the connection is released, complete the leader gate so
                    // followers re-enter through cooldown cache + DB hit, and
                    // return the cooldown 403 without ever subscribing the
                    // body publisher to storage. Upstream RPS / wall-time
                    // saved vs the legacy HEAD-then-GET path.
                    drainUpstreamBody(body.publisher());
                    singleFlightGate.complete(null);
                    this.auditPrimaryAccess(
                        auditCtx, path, inboundHeaders, 0L,
                        com.auto1.pantera.audit.AuditLogger.OUTCOME_FAILURE,
                        com.auto1.pantera.audit.AuditLogger.REASON_COOLDOWN_ACTIVE
                    );
                    return CompletableFuture.completedFuture(blockResp.get());
                }
                return this.cacheWriter.streamThroughAndCommit(
                    key, upstreamUri, body.size(), body.publisher(),
                    sidecars, null, ctx
                ).toCompletableFuture().thenApply(result -> {
                if (result instanceof Result.Err<ProxyCacheWriter.StreamedArtifact> err) {
                    // streamThroughAndCommit returns Err only for the narrow
                    // case where temp file / channel creation fails BEFORE
                    // the upstream body is subscribed. Upstream errors and
                    // integrity mismatches reach the client via the response
                    // body's onError / log-and-don't-commit paths, not via
                    // this Err branch. Surface as 502 (storage_unavailable
                    // semantics) so RaceSlice can fall through to the next
                    // remote.
                    this.auditPrimaryAccess(
                        auditCtx, path, inboundHeaders, 0L,
                        com.auto1.pantera.audit.AuditLogger.OUTCOME_FAILURE,
                        com.auto1.pantera.audit.AuditLogger.REASON_STORAGE_UNAVAILABLE
                    );
                    return ResponseBuilder.badGateway()
                        .textBody("Upstream temporarily unavailable")
                        .build();
                }
                @SuppressWarnings("unchecked")
                final ProxyCacheWriter.StreamedArtifact artifact =
                    ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
                // M4: release followers waiting on the single-flight gate only
                // AFTER the verify-and-commit step lands the bytes in storage.
                // The Response we return now carries the streaming body — it
                // resolves before the cache is committed, so completing the
                // gate on this future would let followers re-enter
                // verifyAndServePrimary against a still-empty cache and refire
                // upstream.
                artifact.verificationOutcome()
                    .whenComplete((r2, e2) -> singleFlightGate.complete(null));
                // WS4-maven.2: once the sha1-verified primary is committed,
                // additionally verify its `.asc` signature against the
                // keyring and roll back (delete) the commit on failure —
                // fire-and-forget, same posture as the rollback-after-
                // partial-failure elsewhere in the cache-write pipeline
                // (CLAUDE.md). PGP trade-off note: this FIRST requester's
                // response (built below) has already started streaming
                // `artifact.body()` to the client by the time this
                // resolves — identical to the existing sha1 verify-after-
                // stream trade-off this class has shipped since Track 4.
                // What this DOES guarantee: a tampered/unsigned/untrusted
                // primary is never left in the cache, so every subsequent
                // request re-fetches and re-evaluates cleanly.
                if (this.verifyPgp) {
                    ascInflight.ifPresent(asc -> artifact.verificationOutcome()
                        .thenCompose(outcome -> outcome instanceof Result.Ok
                            ? this.verifyPgpAfterCommit(key, asc, auditCtx, path, inboundHeaders)
                            : CompletableFuture.<Void>completedFuture(null))
                        .exceptionally(pgpErr -> {
                            EcsLogger.warn("com.auto1.pantera.maven.http")
                                .message("Post-commit PGP verification failed unexpectedly; "
                                    + "primary left as sha1-verified-only")
                                .eventCategory("file")
                                .eventAction("pgp_verification_failed")
                                .eventOutcome("failure")
                                .field("repository.name", this.repoName())
                                .field("url.path", path)
                                .error(pgpErr)
                                .field("log.source", "application")
                                .log();
                            return null;
                        }));
                }
                // Track 5 Phase 1B: pass the upstream response headers (carrying
                // Last-Modified) through to buildArtifactEvent so the DB
                // consumer records the true upstream publish date for this
                // (artifact, version). Pre-Track 5 we passed Headers.EMPTY,
                // which fell back to System.currentTimeMillis() — making
                // every cooldown evaluation on a freshly-cached version
                // resolve to "just published" and triggering an upstream
                // HEAD via MavenHeadSource on the very next request.
                // Bind the request-context internal headers (set by
                // EcsLoggingSlice on entry) into MDC for THIS worker
                // thread so the ProxyArtifactEvent constructed inside
                // enqueueEventForWriter auto-captures trace.id +
                // client.ip and threads them down to the audit log.
                // Without this, the MDC the .thenApply runs under is
                // empty (MDC is per-thread and was dropped at the
                // earlier async hop).
                com.auto1.pantera.http.log.RequestContextHeaders.bindToMdc(inboundHeaders);
                this.enqueueEventForWriter(
                    key, body.headers(), artifact.body().size().orElse(0L),
                    new com.auto1.pantera.http.headers.Login(inboundHeaders).getValue()
                );
                this.auditPrimaryAccess(
                    auditCtx, path, inboundHeaders, artifact.body().size().orElse(0L),
                    com.auto1.pantera.audit.AuditLogger.OUTCOME_SUCCESS, null
                );
                // WS4-maven.8 (partial): Content-Type/Disposition/Accept-Ranges
                // and Content-Length (when upstream advertised one) so a cold
                // fetch already carries most of local mode's header set. No
                // ETag here — the sha1 isn't known synchronously (verification
                // completes after this response is returned, streaming); the
                // NEXT request for this now-cached artifact gets the full set
                // including ETag via serveFromCache/cache().load().
                final ResponseBuilder freshResp = ResponseBuilder.ok()
                    .header(ArtifactHeaders.contentType(key))
                    .header(ArtifactHeaders.contentDisposition(key))
                    .header("Accept-Ranges", "bytes")
                    .header("Last-Modified", httpDate(Instant.now()));
                artifact.body().size().ifPresent(
                    size -> freshResp.header(new com.auto1.pantera.http.headers.ContentLength(size))
                );
                return freshResp.body(artifact.body()).build();
            });
            })
        ).exceptionally(err -> {
            final Throwable cause = unwrap(err);
            if (cause instanceof UpstreamHttpException upstreamErr) {
                return mapUpstreamStatus(upstreamErr);
            }
            // Connection / timeout / SSL → transient infrastructure.
            return ResponseBuilder.badGateway()
                .textBody("Upstream temporarily unavailable")
                .build();
        });
    }

    /**
     * Evaluate cooldown using the publish-date carried by the upstream
     * GET response's {@code Last-Modified} header, instead of issuing a
     * separate HEAD probe via {@link com.auto1.pantera.publishdate.RegistryBackedInspector}.
     *
     * <p>Returns {@code Optional.of(403)} when the artifact must be
     * blocked; {@code Optional.empty()} when the artifact is allowed
     * (or cooldown is not configured, the request is not cooldown-
     * eligible, or the header is missing/unparseable — fail-open for
     * availability, matching {@code evaluateCooldownOrProceed}).
     */
    private CompletableFuture<Optional<Response>> cooldownAtHeaders(
        final Headers inboundHeaders,
        final String path,
        final UpstreamBody body
    ) {
        if (this.cooldownService == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Optional<com.auto1.pantera.cooldown.api.CooldownRequest> request =
            this.buildCooldownRequest(path, inboundHeaders);
        if (request.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Optional<java.time.Instant> publishDate =
            BaseCachedProxySlice.extractLastModified(body.headers())
                .map(java.time.Instant::ofEpochMilli);
        return this.cooldownService
            .evaluateWithKnownDate(request.get(), publishDate)
            .thenApply(result -> {
                if (result.blocked()) {
                    return Optional.of(
                        BaseCachedProxySlice.buildForbiddenResponse(
                            result.block().orElseThrow(), this.repoType()
                        )
                    );
                }
                return Optional.<Response>empty();
            })
            .exceptionally(err -> {
                // Fail-open on evaluator errors (same posture as
                // BaseCachedProxySlice.evaluateCooldownOrProceed): availability
                // wins over strictness — a broken cooldown service must not
                // block legitimate artifact serving.
                EcsLogger.warn("com.auto1.pantera.maven.http")
                    .message("Header-time cooldown evaluate failed; proceeding without block")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate_failure")
                    .eventOutcome("failure")
                    .field("repository.type", this.repoType())
                    .field("repository.name", this.repoName())
                    .field("url.path", path)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            });
    }

    /**
     * Drain an unsubscribed upstream body Publisher so the underlying
     * HTTP connection is released. Mirrors {@code GroupResolver.drainBody}
     * — discard every chunk, ignore errors. Must be called on the block
     * path because {@code Publisher<ByteBuffer>} accepts exactly one
     * subscriber and Pantera's contract (CLAUDE.md) is that bodies are
     * always consumed, even when discarded.
     */
    private static void drainUpstreamBody(final Publisher<ByteBuffer> publisher) {
        publisher.subscribe(new org.reactivestreams.Subscriber<>() {
            @Override
            public void onSubscribe(final org.reactivestreams.Subscription sub) {
                sub.request(Long.MAX_VALUE);
            }
            @Override
            public void onNext(final ByteBuffer item) {
                // discard
            }
            @Override
            public void onError(final Throwable err) {
                // drain failures are not actionable
            }
            @Override
            public void onComplete() {
                // body fully consumed
            }
        });
    }

    /**
     * W6 status-code fidelity (analysis/plan/v1/PLAN.md, RCA-1 + RCA-7):
     * map upstream non-2xx to the correct outbound response so the group
     * resolver can act on it, the index cache does not get poisoned, and
     * clients receive authoritative auth / rate-limit signals.
     *
     * <ul>
     *   <li><b>404, 410</b> → propagate as 404 ({@code notFound}) so
     *       RaceSlice can try the next remote (e.g. {@code .module} on
     *       maven-central 404 → try plugins.gradle.org).</li>
     *   <li><b>429</b> → propagate as 429 with the upstream's
     *       Retry-After preserved. M3's gate is now closed for this
     *       host so subsequent calls fail-fast; client backs off.</li>
     *   <li><b>503 with Retry-After</b> → propagate as 503 + Retry-After
     *       (transient cooldown).</li>
     *   <li><b>401, 403</b> → propagate verbatim (auth is authoritative,
     *       not a fallthrough signal).</li>
     *   <li><b>5xx (no Retry-After)</b> → 502 badGateway; group fanout
     *       will try the next member. Index cache MUST NOT write a
     *       negative-cache entry for these.</li>
     * </ul>
     */
    private static Response mapUpstreamStatus(final UpstreamHttpException err) {
        final int status = err.status();
        if (status == 404 || status == 410) {
            return ResponseBuilder.notFound().build();
        }
        if (status == 429) {
            final ResponseBuilder rb = ResponseBuilder
                .from(com.auto1.pantera.http.RsStatus.TOO_MANY_REQUESTS);
            err.retryAfter().ifPresent(ra -> rb.header("Retry-After", ra));
            return rb.textBody("Upstream rate-limited").build();
        }
        // 503 WITH Retry-After: upstream cooldown — propagate verbatim.
        // 503 WITHOUT Retry-After: pure transient — fall through to
        // badGateway so the group resolver retries another member
        // without poisoning the cache.
        if (status == 503 && err.retryAfter().isPresent()) {
            return ResponseBuilder
                .from(com.auto1.pantera.http.RsStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", err.retryAfter().get())
                .textBody("Upstream temporarily unavailable")
                .build();
        }
        if (status == 401 || status == 403) {
            return ResponseBuilder
                .from(com.auto1.pantera.http.RsStatus.byCode(status))
                .textBody("Upstream auth required")
                .build();
        }
        // Outbound-breaker fast-fail: keep the marker (and Retry-After)
        // on the re-synthesised 502 so the group resolver skips this
        // member instead of convicting it on the breaker's own output.
        if (err.circuitOpen()) {
            final ResponseBuilder rb = ResponseBuilder.badGateway()
                .header(com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER, "true")
                .textBody("Upstream circuit breaker is open");
            err.retryAfter().ifPresent(ra -> rb.header("Retry-After", ra));
            return rb.build();
        }
        // 5xx and any other unclassified non-2xx — transient,
        // surface as bad-gateway so group fanout retries another member.
        return ResponseBuilder.badGateway()
            .textBody("Upstream temporarily unavailable")
            .build();
    }

    /**
     * Fetch the primary from upstream and return its body Publisher together
     * with the Content-Length (when present) AND the response headers
     * (carrying {@code Last-Modified} for Track 5 Phase 1B publish-date
     * pre-population). The body has NOT been subscribed — the caller
     * (stream-through tee) is responsible for exactly-one subscription. On
     * any non-success status, throws {@link UpstreamHttpException} after
     * draining the response body to release the connection.
     */
    private CompletionStage<UpstreamBody> fetchPrimaryBody(
        final RequestLine line, final Headers inboundHeaders
    ) {
        // Forward the inbound client's User-Agent + Accept so Maven
        // Central / Cloudflare sees `Apache-Maven/...` rather than an
        // empty-UA bot-shaped request (the latter falls into a stricter
        // rate-limit category on Cloudflare-fronted CDNs).
        return this.remote.response(line, this.upstreamHeaders(inboundHeaders), Content.EMPTY)
            .thenApply(resp -> {
                if (!resp.status().success()) {
                    resp.body().asBytesFuture();
                    // Preserve Retry-After so the W6 status-fidelity handler
                    // can propagate it verbatim on 429 / 503.
                    final java.util.List<String> retryAfter =
                        resp.headers().values("Retry-After");
                    throw new UpstreamHttpException(
                        resp.status().code(),
                        retryAfter.isEmpty() ? null : retryAfter.get(0),
                        !resp.headers().values(
                            com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER
                        ).isEmpty()
                    );
                }
                return new UpstreamBody(
                    resp.body().size(), resp.body(), resp.headers()
                );
            });
    }

    /**
     * Upstream response body bundle: optional Content-Length + the unsubscribed
     * body Publisher + response headers. Lives in the adapter (not the writer)
     * so the writer stays decoupled from the slice's HTTP client. Track 5
     * Phase 1B added {@code headers} so the publish-date can be propagated
     * into the artifact event without a second upstream round-trip.
     */
    private record UpstreamBody(
        Optional<Long> size, Publisher<ByteBuffer> publisher, Headers headers
    ) {
    }

    /**
     * Unwrap CompletionException chains to surface the underlying cause for
     * status-mapping checks. Mirrors {@code ProxyCacheWriter.unwrap}.
     */
    private static Throwable unwrap(final Throwable err) {
        Throwable cur = err;
        int depth = 0;
        while (cur instanceof java.util.concurrent.CompletionException
            && cur.getCause() != null && depth < 8) {
            cur = cur.getCause();
            depth++;
        }
        return cur;
    }

    /**
     * Carries the upstream HTTP status (and Retry-After when present) so
     * {@link #fetchVerifyAndCache} can map each non-2xx category to the
     * right outbound response per W6 status-fidelity:
     *
     * <ul>
     *   <li>404 / 410 → propagate as 404 to RaceSlice (genuine "doesn't
     *       have it" — next member may have it).</li>
     *   <li>429 → propagate verbatim with Retry-After (M3's gate honours
     *       this; mvn / npm clients back off).</li>
     *   <li>401 / 403 → propagate verbatim (authoritative auth signal).</li>
     *   <li>503 with Retry-After → propagate verbatim (upstream cooldown).</li>
     *   <li>5xx → badGateway (transient — group fanout retries another
     *       member; cache is not poisoned).</li>
     * </ul>
     */
    private static final class UpstreamHttpException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String retryAfter;
        private final boolean circuitOpen;

        UpstreamHttpException(final int status, final String retryAfter,
            final boolean circuitOpen) {
            super("Upstream returned HTTP " + status);
            this.status = status;
            this.retryAfter = retryAfter;
            this.circuitOpen = circuitOpen;
        }

        int status() {
            return this.status;
        }

        Optional<String> retryAfter() {
            return Optional.ofNullable(this.retryAfter);
        }

        /**
         * True when the 5xx is the outbound breaker's synthesised
         * fast-fail (X-Pantera-Circuit-Open marker), not a response the
         * upstream actually produced.
         */
        boolean circuitOpen() {
            return this.circuitOpen;
        }
    }

    /**
     * Fetch a sidecar for the primary at {@code line}. Returns
     * {@link Optional#empty()} for 4xx/5xx so the writer treats the sidecar
     * as absent; I/O errors collapse to empty so a transient sidecar failure
     * never blocks the primary write.
     */
    private CompletionStage<Optional<InputStream>> fetchSidecar(
        final RequestLine primary, final Headers inboundHeaders, final String extension
    ) {
        final String sidecarPath = primary.uri().getPath() + extension;
        final RequestLine sidecarLine = new RequestLine(
            primary.method().value(), sidecarPath
        );
        return this.remote.response(sidecarLine, this.upstreamHeaders(inboundHeaders), Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return resp.body().asBytesFuture()
                        .thenApply(ignored -> Optional.<InputStream>empty());
                }
                return resp.body().asBytesFuture()
                    .thenApply(bytes -> Optional.<InputStream>of(
                        new java.io.ByteArrayInputStream(bytes)
                    ));
            })
            .exceptionally(ignored -> Optional.<InputStream>empty());
    }

    /**
     * WS4-maven.2: verify the {@code .asc} signature fetched alongside the
     * primary against the bytes storage just committed (sha1-verified).
     * Per the 00-security-integrity-decisions.md/S4 policy, a missing
     * signature is treated the same as a failed one when {@code verifyPgp}
     * is enabled (Maven-Central-tier semantics — reject unless verified).
     *
     * @param key Primary artifact key (already committed to storage)
     * @param ascInflight The {@code .asc} fetch kicked off alongside the primary
     * @param auditCtx Request correlation context
     * @param path Request path (for logging)
     * @param inboundHeaders Inbound request headers (for the access audit owner)
     * @return Completion stage, resolved once verification (and any rollback
     *         delete) finishes
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<Void> verifyPgpAfterCommit(
        final Key key, final CompletionStage<Optional<InputStream>> ascInflight,
        final com.auto1.pantera.audit.AuditContext auditCtx, final String path,
        final Headers inboundHeaders
    ) {
        final Storage storage = this.rawStorage.orElseThrow();
        return ascInflight.thenCompose(ascOpt ->
            storage.value(key).thenCompose(Content::asBytesFuture).thenCompose(primaryBytes -> {
                final byte[] ascBytes = ascOpt.map(CachedProxySlice::readAllQuietly).orElse(null);
                final PgpVerifier.Result result = new PgpVerifier(KeyringStoreRegistry.active())
                    .verify(primaryBytes, ascBytes);
                if (result == PgpVerifier.Result.VERIFIED) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return this.rejectPgpCommit(key, result, auditCtx, path, inboundHeaders);
            })
        );
    }

    /**
     * Roll back a PGP-verification failure: log the state transition, emit
     * the {@code artifact_access} failure audit, and best-effort delete the
     * primary plus its checksum/signature sidecars so the cache does not
     * keep serving what just failed verification.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<Void> rejectPgpCommit(
        final Key key, final PgpVerifier.Result result,
        final com.auto1.pantera.audit.AuditContext auditCtx, final String path,
        final Headers inboundHeaders
    ) {
        EcsLogger.warn("com.auto1.pantera.maven.http")
            .message("PGP verification failed for cached primary (" + result
                + "); removing from cache")
            .eventCategory("file")
            .eventAction("pgp_verification_failed")
            .eventOutcome("failure")
            .field("repository.name", this.repoName())
            .field("url.path", path)
            .field("event.reason", result.name())
            .field("log.source", "application")
            .log();
        this.auditPrimaryAccess(
            auditCtx, path, inboundHeaders, 0L,
            com.auto1.pantera.audit.AuditLogger.OUTCOME_FAILURE,
            com.auto1.pantera.audit.AuditLogger.REASON_CHECKSUM_MISMATCH
        );
        final Storage storage = this.rawStorage.orElseThrow();
        return storage.delete(key)
            .exceptionally(ignored -> null)
            .thenCompose(ignored -> deleteSidecarsQuietly(storage, key));
    }

    /**
     * Best-effort delete of every checksum/signature sidecar for a primary
     * key. Used to fully unpublish a primary that failed post-commit PGP
     * verification. Missing sidecars and delete failures are swallowed —
     * this is cleanup, not a correctness-critical path.
     */
    private static CompletionStage<Void> deleteSidecarsQuietly(final Storage storage, final Key key) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String ext : List.of(".sha1", ".sha256", ".md5", ".sha512", ".asc")) {
            final Key sidecarKey = new Key.From(key.string() + ext);
            chain = chain.thenCompose(
                ignored -> storage.exists(sidecarKey).thenCompose(
                    exists -> exists
                        ? storage.delete(sidecarKey).exceptionally(ignored2 -> null)
                        : CompletableFuture.<Void>completedFuture(null)
                )
            );
        }
        return chain;
    }

    /**
     * Read an already-buffered {@link InputStream} (produced by
     * {@link #fetchSidecar}, which wraps fully-collected bytes in a
     * {@link java.io.ByteArrayInputStream}) fully into a byte array.
     * @param in Stream to drain
     * @return All bytes, or an empty array if the stream could not be read
     *         — {@link PgpVerifier#verify} treats an empty array the same
     *         as a missing signature, so this is not a silent data loss
     */
    private static byte[] readAllQuietly(final InputStream in) {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (final IOException ex) {
            return new byte[0];
        }
    }

    /**
     * Serve the primary from storage after a successful atomic write, or on
     * a plain cache hit. Emits an {@code AuditLogger#access} success event —
     * the artifact was already published the first time it was cached, so
     * this is a read, not a publish. Carries the same validator/content
     * headers as local mode (WS4-maven.8) and honours {@code If-None-Match}
     * (WS4-maven.7) — checked BEFORE the body is read from storage, so a
     * revalidation never pays for an unnecessary read.
     */
    private CompletableFuture<Response> serveFromCache(
        final Storage storage, final Key key,
        final com.auto1.pantera.audit.AuditContext auditCtx,
        final String path, final Headers headers
    ) {
        return this.serveArtifactWithHeaders(storage, key, headers, () -> storage.value(key))
            .thenApply(resp -> {
                this.auditPrimaryAccess(
                    auditCtx, path, headers, contentLength(resp),
                    com.auto1.pantera.audit.AuditLogger.OUTCOME_SUCCESS, null
                );
                return resp;
            });
    }

    /**
     * Shared cache-hit response builder for {@link #serveFromCache} and the
     * {@code cache().load()} hit branch in {@link #verifyAndServePrimary}:
     * builds the sha1 {@code ETag} + {@code Last-Modified} from storage,
     * returns 304 on a matching {@code If-None-Match} without ever invoking
     * {@code contentSupplier}, and otherwise attaches the full local-mode
     * header set ({@link ArtifactHeaders}, {@code Accept-Ranges},
     * {@code Content-Length} when known) to the 200.
     *
     * @param storage Raw storage backing this proxy's cache
     * @param key Artifact key
     * @param headers Inbound request headers (read for {@code If-None-Match})
     * @param contentSupplier Lazily supplies the body — invoked only when a
     *                        200 (not 304) will be served
     * @return Response future
     */
    private CompletableFuture<Response> serveArtifactWithHeaders(
        final Storage storage, final Key key, final Headers headers,
        final Supplier<CompletionStage<Content>> contentSupplier
    ) {
        return storage.exists(key).thenCompose(existsInStorage -> {
            if (!existsInStorage) {
                // The artifact came back from the abstract Cache but is not
                // (or not yet) present in raw storage under this key — there
                // is nothing to read metadata/checksums from. Serve the
                // bytes we have without the enhanced validator headers
                // rather than fail the request. In production `cache()` is
                // always backed by the same storage `rawStorage` wraps
                // (FromStorageCache), so this is a defensive fallback for a
                // cache implementation that is genuinely storage-independent
                // (e.g. a test double), not the steady-state path.
                return contentSupplier.get().thenApply(
                    content -> ResponseBuilder.ok().body(content).build()
                ).toCompletableFuture();
            }
            return storage.metadata(key).thenCombine(
                new RepositoryChecksums(storage).checksums(key),
                (meta, checksums) -> {
                    final String etag = checksums.get("sha1");
                    final Header lastModified = LastModifiedHeader.from(meta);
                    if (ArtifactConditionalGet.matches(headers, etag)) {
                        return CompletableFuture.completedFuture(
                            ArtifactConditionalGet.notModified(etag, lastModified)
                        );
                    }
                    return contentSupplier.get().thenApply(content -> {
                        final ResponseBuilder resp = ResponseBuilder.ok()
                            .headers(ArtifactHeaders.from(key, checksums))
                            .header(lastModified)
                            .header("Accept-Ranges", "bytes");
                        content.size().ifPresent(size -> resp.header(new ContentLength(size)));
                        return resp.body(content).build();
                    }).toCompletableFuture();
                }
            ).thenCompose(Function.identity());
        });
    }

    /**
     * @param resp Response built by {@link #serveArtifactWithHeaders}
     * @return {@code Content-Length} when present in the response headers
     *         (200 case), 0 otherwise (304 — no body, nothing transferred)
     */
    private static long contentLength(final Response resp) {
        final List<String> values = resp.headers().values("Content-Length");
        if (values.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(values.get(0));
        } catch (final NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Emit an {@code AuditLogger#access} event for the primary-artifact
     * path, deriving artifact name/version from {@link
     * #buildCooldownRequest(String, Headers)} — the same per-adapter parser
     * cooldown already uses — falling back to the raw request path when it
     * doesn't resolve.
     */
    private void auditPrimaryAccess(
        final com.auto1.pantera.audit.AuditContext auditCtx, final String path,
        final Headers headers, final long size, final String outcome, final String reason
    ) {
        final Optional<CooldownRequest> parsed = this.buildCooldownRequest(path, headers);
        final String artifactName = parsed.map(CooldownRequest::artifact).orElse(path);
        final String version = parsed.map(CooldownRequest::version).orElse(null);
        com.auto1.pantera.audit.AuditLogger.access(
            auditCtx, this.repoType(), this.repoName(), artifactName, version, size,
            new Login(headers).getValue(), outcome, reason
        );
    }

    /**
     * Enqueue a proxy-artifact event after a successful {@link ProxyCacheWriter}
     * write. Mirrors {@code BaseCachedProxySlice.enqueueEvent} for the new
     * verify-then-write path so Maven/Gradle proxies generate DB-index events
     * the same way the legacy {@code fetchAndCache} path does.
     *
     * <p>Track 5 Phase 1B fix: the upstream response headers are now threaded
     * through {@link UpstreamBody} so {@code buildArtifactEvent} can extract
     * the authoritative {@code Last-Modified} timestamp. Pre-Track 5 this
     * call passed {@code Headers.EMPTY}, so the DB consumer fell back to
     * {@code System.currentTimeMillis()} as the publish date — and the next
     * cooldown evaluation for that same {@code (artifact, version)} found a
     * timestamp of "right now" in the registry, decided the version was
     * still inside the cooldown window, and (worse) on a cache eviction
     * fell through to {@code MavenHeadSource} to re-resolve. Net: every
     * stream-through cache write quietly created an upstream HEAD debt
     * paid the next time the publish-date L1 evicted that key.</p>
     *
     * <p>Any exception in the enqueue path is swallowed so the serve path
     * (the {@code return serveFromCache(...)} that follows this call) is
     * never affected by a queue failure.</p>
     *
     * @param key            Artifact cache key.
     * @param upstreamHeaders Upstream response headers carrying
     *                        {@code Last-Modified}; may be {@link Headers#EMPTY}
     *                        when upstream omits the header.
     * @param size           Artifact size in bytes (0 when unavailable).
     */
    private void enqueueEventForWriter(
        final Key key, final Headers upstreamHeaders, final long size,
        final String owner
    ) {
        if (this.localEvents.isEmpty()) {
            return;
        }
        try {
            final Optional<ProxyArtifactEvent> event = this.buildArtifactEvent(
                key, upstreamHeaders, size, owner
            );
            event.ifPresent(e -> {
                if (!this.localEvents.get().offer(e)) {
                    com.auto1.pantera.metrics.EventsQueueMetrics
                        .recordDropped(this.repoName());
                }
            });
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.maven.http")
                .message("Failed to enqueue proxy event; serve path unaffected")
                .eventCategory("process")
                .eventAction("queue_enqueue")
                .eventOutcome("failure")
                .field("repository.name", this.repoName())
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }
}
