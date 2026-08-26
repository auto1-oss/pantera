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
package com.auto1.pantera.docker.http.blobs;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.BlobUnknownError;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;

import com.auto1.pantera.http.log.EcsLogger;
import org.slf4j.MDC;

import java.net.URI;
import java.security.Permission;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code GET /v2/<name>/blobs/<digest>} -- OCI/Distribution blob GET, the
 * canonical WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2)
 * presigned-direct-download case: the distribution spec explicitly permits
 * blob-GET redirects, and this is how S3-backed registries serve layers at
 * scale.
 *
 * <p>Dispatch: the repository's configured {@link DownloadMode} ({@link
 * Docker#downloadPolicy()}) gates whether a redirect is even attempted --
 * {@link DownloadMode#STREAM} never calls {@link Blob#presignedUrl(long)}
 * at all, byte-identical to pre-2.3.0 behaviour. {@link DownloadMode#REDIRECT}/
 * {@link DownloadMode#AUTO} attempt one and fall back to streaming through
 * {@link #stream} whenever it is not currently possible (object not yet
 * durably in the blob store, or the backend has no presigner configured) --
 * the spec's mandatory fallback.</p>
 */
public class GetBlobsSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail.
     */
    private static final String REPO_TYPE = "docker";

    public GetBlobsSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final BlobsRequest request = BlobsRequest.from(line);
        // Captured before the async hop -- MDC does not survive worker-thread
        // continuations (CLAUDE.md audit rules: captureAuditContext at the
        // top of the slice).
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP));
        final String owner = new Login(headers).getValue();
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name())
                .layers().get(request.digest())
                .thenCompose(
                    found -> found.map(
                        blob -> this.serve(blob, request, ctx, owner)
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new BlobUnknownError(request.digest()).json())
                            .completedFuture()
                    )
                )
                .exceptionally(err -> {
                    EcsLogger.warn("com.auto1.pantera.docker")
                        .message("Blob GET failed with exception, returning 404")
                        .eventCategory("web")
                        .eventAction("blob_get")
                        .eventOutcome("failure")
                        .field("package.checksum", request.digest().string())
                        .error(err)
                        .field("log.source", "application")
                        .log();
                    return ResponseBuilder.notFound()
                        .jsonBody(new BlobUnknownError(request.digest()).json())
                        .build();
                })
        );
    }

    /**
     * WS1.7 serving decision: never attempt a redirect in {@code stream}
     * mode; otherwise attempt one and fall back to {@link #stream} when the
     * blob reports none is currently possible.
     */
    private CompletableFuture<Response> serve(
        final Blob blob, final BlobsRequest request, final AuditContext ctx, final String owner
    ) {
        final DownloadMode mode = this.docker.downloadPolicy().mode();
        final Optional<URI> presigned = mode == DownloadMode.STREAM
            ? Optional.empty()
            : blob.presignedUrl(this.docker.downloadPolicy().presignTtlSeconds());
        return presigned
            .map(location -> this.redirect(blob, request, location, ctx, owner))
            .orElseGet(() -> this.stream(blob, request));
    }

    /**
     * Pre-WS1.7 behaviour, unchanged: stream the blob's bytes through
     * Pantera.
     */
    private CompletableFuture<Response> stream(final Blob blob, final BlobsRequest request) {
        this.recordDecision("stream");
        return blob.content()
            .thenCompose(
                content -> content.size()
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(blob::size)
                    .thenApply(
                        size -> ResponseBuilder.ok()
                            .header(new DigestHeader(request.digest()))
                            .header(ContentType.mime("application/octet-stream"))
                            .body(new Content.From(size, content))
                            .build()
                    )
            );
    }

    /**
     * WS1.7: answer with a {@code 302} to the presigned URL instead of
     * streaming -- zero blob-store round trip and zero byte-serving on
     * Pantera's side. Audited as {@code artifact_access} (success) with the
     * digest standing in for "version" (blobs are content-addressed, not
     * tag-versioned), matching {@code DeleteBlobSlice}'s convention.
     */
    private CompletableFuture<Response> redirect(
        final Blob blob, final BlobsRequest request, final URI location,
        final AuditContext ctx, final String owner
    ) {
        return blob.size().exceptionally(err -> 0L).thenApply(size -> {
            this.recordDecision("redirect");
            AuditLogger.access(
                ctx, REPO_TYPE, this.docker.registryName(), request.name(),
                request.digest().string(), size, owner, AuditLogger.OUTCOME_SUCCESS, null
            );
            EcsLogger.info("com.auto1.pantera.docker")
                .message("Blob GET redirected to presigned URL")
                .eventCategory("web")
                .eventAction("blob_get_redirect")
                .eventOutcome("success")
                .field("container.image.name", request.name())
                .field("package.checksum", request.digest().string())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.found()
                .header(new Location(location.toString()))
                .build();
        });
    }

    /**
     * WS1.7 (spec &sect;3.G/WS7): records the redirect-vs-stream serving
     * decision -- see {@code MicrometerMetrics#recordDownloadDecision}.
     */
    private void recordDecision(final String decision) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordDownloadDecision(this.docker.registryName(), decision);
        }
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
