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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.RangeSlice;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.maven.asto.RepositoryChecksums;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link Slice} based on a {@link Storage}. This is the main entrypoint
 * for dispatching GET requests for artifacts.
 */
final class LocalMavenSlice implements Slice {

    /**
     * All supported Maven artifacts according to
     * <a href="https://maven.apache.org/ref/3.6.3/maven-core/artifact-handlers.html">Artifact
     * handlers</a> by maven-core, and additionally {@code xml} metadata files are also artifacts.
     */
    private static final Pattern PTN_ARTIFACT =
        Pattern.compile(String.format(".+\\.(?:%s|xml)", String.join("|", MavenSlice.EXT)));

    /**
     * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2) redirect-eligible
     * extensions: the trailing extension of a <em>real, immutable binary
     * artifact</em>. A key is eligible only when its <em>last</em> extension is
     * one of these -- which structurally excludes every sidecar
     * ({@code .jar.sha1}, {@code .pom.asc}, {@code .module.md5}, ...) because
     * their last extension is the checksum/signature, never the artifact type --
     * and excludes {@code maven-metadata.xml} (last extension {@code xml}, not
     * listed here). Those MUST stream so 2.3.0 group-metadata checksum
     * recomputation and checksum/signature serving are never bypassed.
     */
    private static final Set<String> REDIRECT_EXT =
        Set.of("jar", "war", "ear", "rar", "zip", "aar", "pom", "module");

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * WS1.7 per-repo presigned-direct-download policy. {@link
     * DownloadPolicy#streamOnly()} (the two-arg-ctor default) is byte-identical
     * to pre-WS1.7 behaviour: {@link #redirectResponse} never issues a 302.
     */
    private final DownloadPolicy policy;

    /**
     * New local {@code GET} slice -- stream-only (pre-WS1.7 behaviour).
     *
     * @param storage Repository storage
     * @param repoName Repository name
     */
    LocalMavenSlice(final Storage storage, final String repoName) {
        this(storage, repoName, DownloadPolicy.streamOnly());
    }

    /**
     * New local {@code GET} slice with an explicit WS1.7 download policy.
     *
     * @param storage Repository storage
     * @param repoName Repository name
     * @param policy WS1.7 download policy
     */
    LocalMavenSlice(final Storage storage, final String repoName, final DownloadPolicy policy) {
        this.storage = storage;
        this.repoName = repoName;
        this.policy = policy;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = new KeyFromPath(line.uri().getPath());
        final Optional<Response> redirect = this.redirectResponse(line, key);
        if (redirect.isPresent()) {
            return CompletableFuture.completedFuture(redirect.get());
        }
        final Matcher match = LocalMavenSlice.PTN_ARTIFACT.matcher(new KeyLastPart(key).get());
        return match.matches()
            ? artifactResponse(line, headers, key)
            : plainResponse(line.method(), key);
    }

    /**
     * WS1.7 serving decision: on a redirect-eligible binary-artifact GET,
     * resolve a presigned URL and answer {@code 302 + Location} when one is
     * currently possible (durably present + presigner configured); otherwise
     * record the {@code stream} decision and return empty so the caller falls
     * through to the unchanged streaming/conditional-GET/Range path. STREAM
     * mode, non-GET methods and metadata/checksum/signature keys never reach
     * the resolver (see {@link #REDIRECT_EXT}).
     *
     * @param line Request line
     * @param key Artifact key
     * @return A {@code 302} response, or empty to serve normally
     */
    private Optional<Response> redirectResponse(final RequestLine line, final Key key) {
        if (this.policy.mode() == DownloadMode.STREAM
            || line.method() != RqMethod.GET
            || !LocalMavenSlice.redirectEligible(key)) {
            return Optional.empty();
        }
        final Optional<URI> presigned = PresignResolver.resolve(this.storage, key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
        final Optional<Response> result;
        if (presigned.isPresent()) {
            this.recordMetric(() ->
                com.auto1.pantera.metrics.PanteraMetrics.instance().download(this.repoName, "maven")
            );
            this.recordDownloadDecision("redirect");
            result = Optional.of(
                ResponseBuilder.found().header(new Location(presigned.get().toString())).build()
            );
        } else {
            this.recordDownloadDecision("stream");
            result = Optional.empty();
        }
        return result;
    }

    /**
     * @param key Artifact key
     * @return {@code true} iff the key's last extension addresses a real binary
     *  artifact ({@link #REDIRECT_EXT}) -- never a checksum/signature sidecar
     *  or {@code maven-metadata.xml}
     */
    private static boolean redirectEligible(final Key key) {
        final String last = new KeyLastPart(key).get();
        final int dot = last.lastIndexOf('.');
        return dot >= 0
            && LocalMavenSlice.REDIRECT_EXT.contains(
                last.substring(dot + 1).toLowerCase(Locale.ROOT)
            );
    }

    /**
     * WS1.7: record the redirect-vs-stream serving decision (only ever called
     * for redirect-eligible routes -- {@code policy.mode() != STREAM}).
     *
     * @param decision {@code "redirect"} or {@code "stream"}
     */
    private void recordDownloadDecision(final String decision) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordDownloadDecision(this.repoName, decision);
        }
    }

    /**
     * Artifact response for repository artifact request.
     * @param line Request line
     * @param headers Request headers
     * @param artifact Artifact key
     * @return Response
     */
    private CompletableFuture<Response> artifactResponse(
        final RequestLine line, final Headers headers, final Key artifact
    ) {
        return switch (line.method()) {
            case GET -> this.getArtifact(line, headers, artifact);
            case HEAD -> storage.exists(artifact)
                .thenCompose(
                    exists -> {
                        if (!exists) {
                            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                        }
                        return storage.metadata(artifact).thenCombine(
                            new RepositoryChecksums(storage).checksums(artifact),
                            (meta, checksums) -> {
                                final ResponseBuilder resp = ResponseBuilder.ok()
                                    .headers(ArtifactHeaders.from(artifact, checksums))
                                    .header(LastModifiedHeader.from(meta));
                                // Omit Content-Length rather than 500 on a backend
                                // that reports no size (all real backends report it).
                                meta.read(Meta.OP_SIZE)
                                    .ifPresent(size -> resp.header(new ContentLength(size)));
                                return resp.build();
                            }
                        );
                    }
                );
            default -> CompletableFuture.completedFuture(ResponseBuilder.methodNotAllowed().build());
        };
    }

    /**
     * {@code GET} for a local artifact (or {@code maven-metadata.xml}, which
     * shares this path since it also matches {@link #PTN_ARTIFACT}).
     *
     * <ul>
     *   <li>404 when the key is absent.</li>
     *   <li>304 (WS4-maven.7) when the inbound {@code If-None-Match} matches
     *       the sha1 {@code ETag} — no body is read from storage.</li>
     *   <li>200 with {@code Content-Length} (WS4-maven.9) and, for real
     *       binary artifacts only (never {@code maven-metadata.xml}),
     *       {@code Accept-Ranges: bytes} and {@code Range} support
     *       (WS4-maven.11) via {@link RangeSlice}.</li>
     * </ul>
     *
     * @param line Request line, threaded through to {@link RangeSlice}
     * @param headers Request headers (read for {@code If-None-Match} / {@code Range})
     * @param artifact Artifact key
     * @return Response
     */
    private CompletableFuture<Response> getArtifact(
        final RequestLine line, final Headers headers, final Key artifact
    ) {
        return storage.exists(artifact).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            this.recordMetric(() ->
                com.auto1.pantera.metrics.PanteraMetrics.instance().download(this.repoName, "maven")
            );
            return storage.metadata(artifact).thenCombine(
                new RepositoryChecksums(storage).checksums(artifact),
                (meta, checksums) -> this.serveArtifact(line, headers, artifact, meta, checksums)
            ).thenCompose(Function.identity());
        });
    }

    /**
     * Decide 304 vs 200 (and, for 200, whether {@link RangeSlice} applies)
     * once the artifact's metadata and checksums are known.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> serveArtifact(
        final RequestLine line, final Headers headers, final Key artifact,
        final Meta meta, final Map<String, String> checksums
    ) {
        final String etag = checksums.get("sha1");
        final Header lastModified = LastModifiedHeader.from(meta);
        if (ArtifactConditionalGet.matches(headers, etag)) {
            return CompletableFuture.completedFuture(
                ArtifactConditionalGet.notModified(etag, lastModified)
            );
        }
        final boolean metadataFile = isMetadataFile(artifact);
        final Slice bodySlice = (l, h, b) -> StorageArtifactSlice.optimizedValue(this.storage, artifact)
            .thenApply(bodyContent -> {
                final ResponseBuilder resp = ResponseBuilder.ok()
                    .headers(ArtifactHeaders.from(artifact, checksums))
                    .header(lastModified);
                meta.read(Meta.OP_SIZE).ifPresent(size -> resp.header(new ContentLength(size)));
                if (!metadataFile) {
                    // Advertised (and honoured, via the RangeSlice wrap below)
                    // only for real binary artifacts — never maven-metadata.xml
                    // (WS4-maven.11 explicitly excludes metadata from Range).
                    resp.header("Accept-Ranges", "bytes");
                }
                return resp.body(bodyContent).build();
            });
        return metadataFile
            ? bodySlice.response(line, headers, Content.EMPTY)
            : new RangeSlice(bodySlice).response(line, headers, Content.EMPTY);
    }

    /**
     * @param artifact Artifact key
     * @return True when the key is a GA-level {@code maven-metadata.xml}
     *         (which shares {@link #getArtifact} with real artifacts because
     *         {@link #PTN_ARTIFACT} matches {@code .xml} too)
     */
    private static boolean isMetadataFile(final Key artifact) {
        return artifact.string().endsWith("maven-metadata.xml");
    }

    /**
     * Plain response for non-artifact requests.
     * @param method Request method
     * @param key Location
     * @return Response
     */
    private CompletableFuture<Response> plainResponse(final RqMethod method, final Key key) {
        return switch (method) {
            case GET -> plainResponse(
                this.storage, key,
                // Use optimized value retrieval for metadata files too
                () -> StorageArtifactSlice.optimizedValue(this.storage, key)
                    .thenApply(val -> ResponseBuilder.ok().body(val).build())
            );
            case HEAD -> plainResponse(this.storage, key,
                () -> this.storage.metadata(key)
                    .thenApply(
                        meta -> ResponseBuilder.ok()
                            .header(new ContentLength(meta.read(Meta.OP_SIZE).orElseThrow()))
                            .build()
                    )
            );
            default -> CompletableFuture.completedFuture(ResponseBuilder.methodNotAllowed().build());
        };
    }

    private static CompletableFuture<Response> plainResponse(
        Storage storage, Key key, Supplier<CompletableFuture<Response>> actual
    ) {
        return storage.exists(key)
            .thenApply(
                exists -> exists
                    ? actual.get()
                    : CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
            ).thenCompose(Function.identity());

    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
     */
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Failed to record metric")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }
}
