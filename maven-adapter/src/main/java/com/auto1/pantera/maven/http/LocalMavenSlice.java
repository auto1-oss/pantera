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
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.maven.asto.RepositoryChecksums;

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
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * New local {@code GET} slice.
     *
     * @param storage Repository storage
     * @param repoName Repository name
     */
    LocalMavenSlice(Storage storage, String repoName) {
        this.storage = storage;
        this.repoName = repoName;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = new KeyFromPath(line.uri().getPath());
        final Matcher match = LocalMavenSlice.PTN_ARTIFACT.matcher(new KeyLastPart(key).get());
        return match.matches()
            ? artifactResponse(line.method(), key)
            : plainResponse(line.method(), key);
    }

    /**
     * Artifact response for repository artifact request.
     * @param method Method
     * @param artifact Artifact key
     * @return Response
     */
    private CompletableFuture<Response> artifactResponse(final RqMethod method, final Key artifact) {
        return switch (method) {
            case GET -> storage.exists(artifact)
                .thenApply(
                    exists -> {
                        if (exists) {
                            // Track download metric
                            this.recordMetric(() ->
                                com.auto1.pantera.metrics.PanteraMetrics.instance().download(this.repoName, "maven")
                            );
                            // Use storage-specific optimized content retrieval for 100-1000x faster downloads
                            return StorageArtifactSlice.optimizedValue(storage, artifact)
                                .thenCombine(
                                    new RepositoryChecksums(storage).checksums(artifact),
                                    (body, checksums) ->
                                        ResponseBuilder.ok()
                                            .headers(ArtifactHeaders.from(artifact, checksums))
                                            .body(body)
                                            .build()
                                );
                        }
                        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                    }
                ).thenCompose(Function.identity());
            case HEAD ->
//                new ArtifactHeadResponse(this.storage, artifact);
                storage.exists(artifact).thenApply(
                    exists -> {
                        if (exists) {
                            return new RepositoryChecksums(storage)
                                .checksums(artifact)
                                .thenApply(
                                    checksums -> ResponseBuilder.ok()
                                        .headers(ArtifactHeaders.from(artifact, checksums))
                                        .build()
                                );
                        }
                        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                    }
                ).thenCompose(Function.identity());
            default -> CompletableFuture.completedFuture(ResponseBuilder.methodNotAllowed().build());
        };
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
                .log();
        }
    }
}
