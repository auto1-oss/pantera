/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
import com.auto1.pantera.http.cache.SidecarFile;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
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
     * Maven-specific metadata cache for maven-metadata.xml files.
     */
    private final MetadataCache metadataCache;

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
}
