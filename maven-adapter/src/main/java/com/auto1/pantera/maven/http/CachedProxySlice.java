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
@SuppressWarnings("PMD.ExcessiveImports")
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
    @SuppressWarnings("PMD.ExcessiveParameterList")
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
    @SuppressWarnings("PMD.ExcessiveParameterList")
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
        this.cacheWriter = storage
            .map(raw -> new ProxyCacheWriter(raw, repoName))
            .orElse(null);
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
    @SuppressWarnings("PMD.ExcessiveParameterList")
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
        // cache-miss. Runs only when we have a file-backed storage and the
        // requested path is a primary artifact. Cache-hit and sidecar paths
        // fall through to the standard BaseCachedProxySlice flow unchanged.
        // Security: both the cache-hit and cache-miss branches inside
        // verifyAndServePrimary bypass evaluateCooldownAndFetch entirely, so
        // we gate this path through evaluateCooldownOrProceed first. An
        // already-cached blocked version is therefore still refused even if
        // the block was applied after the version was first cached.
        if (this.cacheWriter != null
            && !isChecksumSidecar(path)
            && isPrimaryArtifact(path)) {
            return Optional.of(this.verifyAndServePrimaryGated(line, headers, key, path));
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
        final Optional<Long> lastModified = extractLastModified(responseHeaders);
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
     * Cooldown-gated wrapper around {@link #verifyAndServePrimary}.
     *
     * <p>Delegates to {@link BaseCachedProxySlice#evaluateCooldownOrProceed}
     * so both the cache-hit and cache-miss branches inside
     * {@code verifyAndServePrimary} are guarded by a cooldown evaluation
     * before any storage access or upstream fetch occurs. If the version is
     * blocked, a 403 is returned immediately — even for versions that were
     * cached before the block was applied.</p>
     *
     * @param line    Request line
     * @param headers Request headers (forwarded to cooldown request builder)
     * @param key     Cache key
     * @param path    Request path
     * @return Response future
     */
    private CompletableFuture<Response> verifyAndServePrimaryGated(
        final RequestLine line, final Headers headers, final Key key, final String path
    ) {
        return this.evaluateCooldownOrProceed(
            headers, path, () -> this.verifyAndServePrimary(line, key, path)
        );
    }

    /**
     * Primary-artifact flow: if the cache already has the primary, fall
     * through to the standard flow (serving from cache); otherwise fetch the
     * primary + every sidecar upstream in one coupled batch, verify digests,
     * atomically commit, and serve the freshly-cached bytes.
     *
     * <p>We consult BOTH the {@link Storage} and the {@link Cache} abstraction
     * so tests that plug a lambda-Cache without a real storage keep working,
     * and production file-backed deployments benefit from the verify path on
     * genuine cache misses.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
    private CompletableFuture<Response> verifyAndServePrimary(
        final RequestLine line, final Key key, final String path
    ) {
        final Storage storage = this.rawStorage.orElseThrow();
        return storage.exists(key).thenCompose(presentInStorage -> {
            if (presentInStorage) {
                return this.serveFromCache(storage, key);
            }
            return this.cache().load(
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
            }).toCompletableFuture();
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
     * Fetch the primary + every sidecar, verify via
     * {@link ProxyCacheWriter#writeAndVerify}, then serve the primary
     * directly from the verified temp file while committing to storage
     * asynchronously. Integrity failures and storage failures both collapse
     * to a clean 502 response (mirroring {@code FaultTranslator.UpstreamIntegrity}
     * policy) and leave the cache empty for this key.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
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

        return this.cacheWriter.writeAndVerify(
            key,
            upstreamUri,
            () -> this.fetchPrimary(line),
            sidecars,
            ctx
        ).toCompletableFuture().thenCompose(result -> {
            if (result instanceof Result.Err<ProxyCacheWriter.VerifiedArtifact> err) {
                if (err.fault() instanceof Fault.UpstreamIntegrity) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.badGateway()
                            .header("X-Pantera-Fault", "upstream-integrity")
                            .textBody("Upstream integrity verification failed")
                            .build()
                    );
                }
                // Upstream-404 must propagate as 404, not 5xx: RaceSlice's
                // contract is "404 → try the next remote, non-404 → that
                // remote wins." Mapping 404 → 503/502 caused a single remote's
                // 404 to short-circuit the race even when another remote
                // had the artifact (e.g. .module on maven-central vs
                // plugins.gradle.org). Other 4xx are also "doesn't have
                // it" semantically — surface them as 404 too.
                if (err.fault() instanceof Fault.StorageUnavailable storageErr
                    && storageErr.cause() instanceof UpstreamHttpException upstreamErr
                    && upstreamErr.status() >= 400 && upstreamErr.status() < 500) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }
                // StorageUnavailable / anything else → 502 Bad Gateway per
                // group-resolution-redesign spec ("Index miss → proxy
                // upstreams fail → 502"). pypi/composer adapters already
                // use badGateway here; this brings maven into alignment.
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badGateway()
                        .textBody("Upstream temporarily unavailable")
                        .build()
                );
            }
            // Success path: serve directly from the verified temp file,
            // commit to persistent storage asynchronously (fire-and-forget).
            @SuppressWarnings("unchecked")
            final ProxyCacheWriter.VerifiedArtifact artifact =
                ((Result.Ok<ProxyCacheWriter.VerifiedArtifact>) result).value();
            this.enqueueEventForWriter(key, artifact.size());
            artifact.commitAsync();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(artifact.contentFromTempFile()).build()
            );
        });
    }

    /**
     * Read the primary from the upstream as an {@link InputStream}. On any
     * non-success status, throws so the writer's outer exception handler
     * treats it as a transient failure (no cache mutation).
     */
    private CompletionStage<InputStream> fetchPrimary(final RequestLine line) {
        return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (!resp.status().success()) {
                    // Drain body to release connection.
                    resp.body().asBytesFuture();
                    throw new UpstreamHttpException(resp.status().code());
                }
                try {
                    return resp.body().asInputStream();
                } catch (final IOException ex) {
                    throw new IllegalStateException("Upstream body not readable", ex);
                }
            });
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
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
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
     * <p>Upstream headers are not available at this call site (the writer
     * returns only a {@code Result&lt;Void&gt;}), so {@code Headers.EMPTY} is passed
     * to {@link #buildArtifactEvent}. This means {@code lastModified} will be
     * {@code Optional.empty()} — the event processor's existing fallback sets
     * {@code created_date = System.currentTimeMillis()}, which matches the
     * pre-regression behavior for artifacts whose upstream does not send
     * {@code Last-Modified}.</p>
     *
     * <p>Any exception in the enqueue path is swallowed so the serve path
     * (the {@code return serveFromCache(...)} that follows this call) is
     * never affected by a queue failure.</p>
     *
     * @param key Artifact cache key
     * @param size Artifact size in bytes (0 when unavailable)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void enqueueEventForWriter(final Key key, final long size) {
        if (this.localEvents.isEmpty()) {
            return;
        }
        try {
            final Optional<ProxyArtifactEvent> event =
                this.buildArtifactEvent(key, Headers.EMPTY, size, ArtifactEvent.DEF_OWNER);
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
