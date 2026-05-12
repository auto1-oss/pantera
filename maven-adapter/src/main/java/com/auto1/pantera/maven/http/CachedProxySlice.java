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
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.maven.cooldown.MavenMetadataFilter;
import com.auto1.pantera.maven.cooldown.MavenMetadataParser;
import com.auto1.pantera.maven.cooldown.MavenMetadataRequestDetector;
import com.auto1.pantera.maven.cooldown.MavenMetadataRewriter;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.regex.Matcher;

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
     * Track 4 sibling prefetcher: after a successful primary commit, fire
     * a background fetch of the companion artifact (typically the
     * {@code .jar} &harr; {@code .pom} partner). Bounded to one worker
     * per repo so it can't overwhelm upstream. {@code null} when storage
     * is unavailable (cannot happen post-Track 3 — constructor refuses
     * empty storage — but defensive against future refactors).
     */
    private final MavenSiblingPrefetcher siblingPrefetcher;

    /**
     * Cooldown metadata filter service. When present, {@link #handleMetadata}
     * runs the upstream {@code maven-metadata.xml} bytes through the parser /
     * filter / rewriter chain before returning, so fresh versions inside the
     * admin-configured cooldown window are stripped from {@code <versions>}
     * and {@code <latest>} / {@code <release>} are rewritten downward. When
     * null (legacy constructors, tests), responses pass through unfiltered —
     * same behaviour as before the metadata filter was wired.
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Inspector used by the metadata filter to resolve per-version release
     * dates. Holding one instance avoids re-wrapping the remote slice on
     * every request.
     */
    private final CooldownInspector metadataInspector;

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
        this.siblingPrefetcher = new MavenSiblingPrefetcher(
            client, storage.get(), this.cacheWriter, repoName, upstreamUrl
        );
        this.cooldownMetadata = cooldownMetadata;
        this.metadataInspector = cooldownMetadata == null
            ? null
            : new RegistryBackedInspector("maven", PublishDateRegistries.instance());
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
            return Optional.of(this.handleMetadata(line, key));
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
            return Optional.of(this.verifyAndServePrimary(line, headers, key, path));
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
        final String version = pkg.substring(idx + 1);
        final String artifact = MavenSlice.EVENT_INFO.formatArtifactName(
            pkg.substring(0, idx)
        );
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
     * window are stripped from {@code <versions>} and {@code <latest>} /
     * {@code <release>} are rewritten to the newest non-blocked version.
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
        final RequestLine line, final Key key
    ) {
        final CompletableFuture<Optional<Content>> loaded = this.metadataCache.load(
            key,
            () -> this.client().response(line, Headers.EMPTY, Content.EMPTY)
                .thenApply(resp -> {
                    if (resp.status().success()) {
                        return Optional.of(resp.body());
                    }
                    return Optional.<Content>empty();
                })
        );
        return loaded.thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            if (this.cooldownMetadata == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "text/xml")
                        .body(opt.get())
                        .build()
                );
            }
            return this.applyMetadataCooldown(line, opt.get());
        });
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
        final RequestLine line, final Content content
    ) {
        final Optional<String> pkgOpt = new MavenMetadataRequestDetector()
            .extractPackageName(line.uri().getPath());
        if (pkgOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "text/xml")
                    .body(content)
                    .build()
            );
        }
        // extractPackageName returns SLASHED format (com/google/guava/guava)
        // — the MavenHeadSource that resolves release dates splits on the last
        // DOT to derive groupId/artifactId, so a slashed name silently produces
        // an empty inspector lookup and the filter fails open ("0 blocked"
        // even when the version is well past its publish-date window). Convert
        // to dotted before handing it to the metadata service. Mirrors the
        // same conversion applied in MavenGroupSlice.applyCooldownFilter.
        final String packageName = pkgOpt.get().replace('/', '.');
        return content.asBytesFuture().thenCompose(
            bytes -> this.cooldownMetadata.filterMetadata(
                this.repoType(),
                this.repoName(),
                packageName,
                bytes,
                new MavenMetadataParser(),
                new MavenMetadataFilter(),
                new MavenMetadataRewriter(),
                Optional.ofNullable(this.metadataInspector)
            ).handle((filtered, ex) -> {
                if (ex == null) {
                    return ResponseBuilder.ok()
                        .header("Content-Type", "text/xml")
                        .body(filtered)
                        .build();
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
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", "text/xml")
                    .body(bytes)
                    .build();
            })
        );
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
        final Storage storage = this.rawStorage.orElseThrow();
        return storage.exists(key).thenCompose(presentInStorage -> {
            if (presentInStorage) {
                return this.serveFromCache(storage, key);
            }
            return this.evaluateCooldownOrProceed(headers, path, () ->
                this.cache().load(
                    key,
                    com.auto1.pantera.asto.cache.Remote.EMPTY,
                    com.auto1.pantera.asto.cache.CacheControl.Standard.ALWAYS
                ).thenCompose(opt -> {
                    if (opt.isPresent()) {
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.ok().body(opt.get()).build()
                        );
                    }
                    return this.fetchVerifyAndCache(line, key, path);
                }).toCompletableFuture()
            );
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.cache")
                .message("Primary-artifact verify-and-serve failed; falling back to not-found")
                .eventCategory("web")
                .eventAction("cache_write")
                .eventOutcome("failure")
                .field("repository.name", this.repoName())
                .field("url.path", path)
                .error(err)
                .log();
            return ResponseBuilder.notFound().build();
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
        final RequestLine line, final Key key, final String path
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
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, () -> this.fetchSidecar(line, ".sha1"));
        return this.fetchPrimaryBody(line).toCompletableFuture().thenCompose(body ->
            this.cacheWriter.streamThroughAndCommit(
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
                    return ResponseBuilder.badGateway()
                        .textBody("Upstream temporarily unavailable")
                        .build();
                }
                @SuppressWarnings("unchecked")
                final ProxyCacheWriter.StreamedArtifact artifact =
                    ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
                // Track 5 Phase 1B: pass the upstream response headers (carrying
                // Last-Modified) through to buildArtifactEvent so the DB
                // consumer records the true upstream publish date for this
                // (artifact, version). Pre-Track 5 we passed Headers.EMPTY,
                // which fell back to System.currentTimeMillis() — making
                // every cooldown evaluation on a freshly-cached version
                // resolve to "just published" and triggering an upstream
                // HEAD via MavenHeadSource on the very next request.
                this.enqueueEventForWriter(
                    key, body.headers(), artifact.body().size().orElse(0L)
                );
                // Track 4 sibling prefetch: fire after the verification +
                // commit lands (verificationOutcome completes only after the
                // primary lands in cache). Failures inside the prefetcher
                // are logged + swallowed; this thenAccept never throws.
                artifact.verificationOutcome().thenAccept(commitResult -> {
                    if (commitResult instanceof Result.Ok) {
                        this.siblingPrefetcher.onPrimaryCached(key);
                    }
                });
                return ResponseBuilder.ok().body(artifact.body()).build();
            })
        ).exceptionally(err -> {
            final Throwable cause = unwrap(err);
            // Upstream-404 must propagate as 404 so RaceSlice can try the
            // next remote (e.g. .module on maven-central 404 → try
            // plugins.gradle.org). Other 4xx are also "doesn't have it"
            // semantically — surface as 404 too.
            if (cause instanceof UpstreamHttpException upstreamErr
                && upstreamErr.status() >= 400 && upstreamErr.status() < 500) {
                return ResponseBuilder.notFound().build();
            }
            return ResponseBuilder.badGateway()
                .textBody("Upstream temporarily unavailable")
                .build();
        });
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
    private CompletionStage<UpstreamBody> fetchPrimaryBody(final RequestLine line) {
        return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (!resp.status().success()) {
                    resp.body().asBytesFuture();
                    throw new UpstreamHttpException(resp.status().code());
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
     * Carries the upstream HTTP status so {@link #fetchVerifyAndCache} can
     * distinguish "this upstream truly doesn't have it" (404 → propagate as
     * 404 to RaceSlice, so other remotes can serve) from "transient failure"
     * (5xx, timeouts → surface as 503). Without this, every non-2xx upstream
     * response was mapped to 503 by the cache writer, and RaceSlice treats
     * 503 as a "winning" response (only 404 triggers race-continue), so a
     * single 404 from maven-central beat a 200 from plugins.gradle.org for
     * Gradle plugin .module files.
     */
    private static final class UpstreamHttpException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int status;

        UpstreamHttpException(final int status) {
            super("Upstream returned HTTP " + status);
            this.status = status;
        }

        int status() {
            return this.status;
        }
    }

    /**
     * Fetch a sidecar for the primary at {@code line}. Returns
     * {@link Optional#empty()} for 4xx/5xx so the writer treats the sidecar
     * as absent; I/O errors collapse to empty so a transient sidecar failure
     * never blocks the primary write.
     */
    private CompletionStage<Optional<InputStream>> fetchSidecar(
        final RequestLine primary, final String extension
    ) {
        final String sidecarPath = primary.uri().getPath() + extension;
        final RequestLine sidecarLine = new RequestLine(
            primary.method().value(), sidecarPath
        );
        return this.remote.response(sidecarLine, Headers.EMPTY, Content.EMPTY)
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
     * Serve the primary from storage after a successful atomic write.
     */
    private CompletableFuture<Response> serveFromCache(final Storage storage, final Key key) {
        return storage.value(key).thenApply(content ->
            ResponseBuilder.ok().body(content).build()
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
        final Key key, final Headers upstreamHeaders, final long size
    ) {
        if (this.localEvents.isEmpty()) {
            return;
        }
        try {
            final Optional<ProxyArtifactEvent> event = this.buildArtifactEvent(
                key, upstreamHeaders, size, ArtifactEvent.DEF_OWNER
            );
            event.ifPresent(e -> {
                if (!this.localEvents.get().offer(e)) {
                    com.auto1.pantera.metrics.EventsQueueMetrics
                        .recordDropped(this.repoName());
                }
            });
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.cache")
                .message("Failed to enqueue proxy event; serve path unaffected")
                .eventCategory("process")
                .eventAction("queue_enqueue")
                .eventOutcome("failure")
                .field("repository.name", this.repoName())
                .error(ex)
                .log();
        }
    }
}
