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
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.cooldown.CooldownRequest;
import com.auto1.pantera.cooldown.CooldownService;
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
     * Constructor with full configuration.
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
        super(
            client, cache, repoName, repoType, upstreamUrl,
            storage, events, config, cooldownService, cooldownInspector
        );
        this.metadataCache = metadataCache;
        this.remote = client;
        this.rawStorage = storage;
        this.cacheWriter = storage
            .map(raw -> new ProxyCacheWriter(raw, repoName))
            .orElse(null);
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
        if (this.cacheWriter != null
            && !isChecksumSidecar(path)
            && isPrimaryArtifact(path)) {
            return Optional.of(this.verifyAndServePrimary(line, key, path));
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
     * @param line Request line
     * @param key Cache key
     * @return Response future
     */
    private CompletableFuture<Response> handleMetadata(
        final RequestLine line, final Key key
    ) {
        return this.metadataCache.load(
            key,
            () -> this.client().response(line, Headers.EMPTY, Content.EMPTY)
                .thenApply(resp -> {
                    if (resp.status().success()) {
                        return Optional.of(resp.body());
                    }
                    return Optional.<Content>empty();
                })
        ).thenApply(opt -> opt
            .map(content -> ResponseBuilder.ok()
                .header("Content-Type", "text/xml")
                .body(content)
                .build()
            )
            .orElse(ResponseBuilder.notFound().build())
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
     * Fetch the primary + every sidecar, verify, commit via
     * {@link ProxyCacheWriter}, then stream the primary from the cache.
     * Integrity failures and storage failures both collapse to a clean 502
     * response (mirroring {@code FaultTranslator.UpstreamIntegrity} policy)
     * and leave the cache empty for this key.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
    private CompletableFuture<Response> fetchVerifyAndCache(
        final RequestLine line, final Key key, final String path
    ) {
        final Storage storage = this.rawStorage.orElseThrow();
        final String upstreamUri = this.upstreamUrl() + path;
        final RequestContext ctx = new RequestContext(
            org.apache.logging.log4j.ThreadContext.get("trace.id"),
            null,
            this.repoName(),
            path
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, () -> this.fetchSidecar(line, ".sha1"));
        sidecars.put(ChecksumAlgo.MD5, () -> this.fetchSidecar(line, ".md5"));
        sidecars.put(ChecksumAlgo.SHA256, () -> this.fetchSidecar(line, ".sha256"));
        sidecars.put(ChecksumAlgo.SHA512, () -> this.fetchSidecar(line, ".sha512"));

        return this.cacheWriter.writeWithSidecars(
            key,
            upstreamUri,
            () -> this.fetchPrimary(line),
            sidecars,
            ctx
        ).toCompletableFuture().thenCompose(result -> {
            if (result instanceof Result.Err<Void> err) {
                if (err.fault() instanceof Fault.UpstreamIntegrity) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.unavailable()
                            .header("X-Pantera-Fault", "upstream-integrity")
                            .textBody("Upstream integrity verification failed")
                            .build()
                    );
                }
                // StorageUnavailable / anything else → 502-equivalent; no cache state.
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unavailable()
                        .textBody("Upstream temporarily unavailable")
                        .build()
                );
            }
            return this.serveFromCache(storage, key);
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
                    throw new IllegalStateException(
                        "Upstream returned HTTP " + resp.status().code()
                    );
                }
                try {
                    return resp.body().asInputStream();
                } catch (final IOException ex) {
                    throw new IllegalStateException("Upstream body not readable", ex);
                }
            });
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
}
