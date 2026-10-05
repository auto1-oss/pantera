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
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.BlobUnknownError;
import com.auto1.pantera.docker.error.DockerReferenceNotFoundException;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@code DELETE /v2/<name>/blobs/<digest>} — OCI/Distribution blob delete
 * (GC). Content-addressed blobs may be shared by more than one manifest;
 * this operation is deliberately independent of {@code DELETE
 * .../manifests/<reference>} ({@link
 * com.auto1.pantera.docker.http.manifest.DeleteManifestSlice}) — deleting a
 * manifest link never cascades into deleting the blobs it references.
 *
 * <p>Hosted ({@code docker}) repositories only: a {@code docker-proxy}
 * answers {@code 405 UNSUPPORTED} before reaching this slice, and the
 * proxy/composite {@code Layers} implementations reject delete with
 * {@link UnsupportedOperationException}, mapped by {@code ErrorHandlingSlice}
 * to {@code 405} — deletes target the authoritative store only.
 */
public final class DeleteBlobSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail — this slice is wired
     * only for {@code docker} (hosted) repositories.
     */
    private static final String REPO_TYPE = "docker";

    /**
     * Ctor.
     *
     * @param docker Docker repository.
     */
    public DeleteBlobSlice(final Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final BlobsRequest request = BlobsRequest.from(line);
        final String owner = new Login(headers).getValue();
        // Captured at slice entry, before any async hop.
        final AuditContext ctx = new AuditContext(headers);
        return body.asBytesFuture().thenCompose(
            ignored -> this.docker.repo(request.name()).layers().delete(request.digest())
        ).<Response>thenApply(
            nothing -> this.deleted(request, headers, owner, ctx)
        ).exceptionally(
            err -> this.failed(request, headers, owner, ctx, err)
        );
    }

    @Override
    public Permission permission(final RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.DELETE.mask()
        );
    }

    /**
     * Success path: audit and answer 202.
     *
     * @param request Blob request.
     * @param headers Request headers.
     * @param owner Requesting user.
     * @param ctx Audit context.
     * @return 202 Accepted.
     */
    private Response deleted(
        final BlobsRequest request, final Headers headers,
        final String owner, final AuditContext ctx
    ) {
        RequestContextHeaders.bindToMdc(headers);
        AuditLogger.delete(
            ctx, REPO_TYPE, this.docker.registryName(), request.name(),
            request.digest().string(), owner, AuditLogger.OUTCOME_SUCCESS, null
        );
        EcsLogger.info("com.auto1.pantera.docker")
            .message("Blob deleted")
            .eventCategory("web")
            .eventAction("blob_delete")
            .eventOutcome("success")
            .field("repository.name", this.docker.registryName())
            .field("user.name", owner)
            .field("container.image.name", request.name())
            .field("package.checksum", request.digest().string())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.accepted().build();
    }

    /**
     * Failure path: 404 BLOB_UNKNOWN for an unknown digest; any other
     * failure (unsupported on proxy/composite, storage error) propagates to
     * {@code ErrorHandlingSlice}.
     *
     * @param request Blob request.
     * @param headers Request headers.
     * @param owner Requesting user.
     * @param ctx Audit context.
     * @param err Failure.
     * @return 404 response.
     */
    private Response failed(
        final BlobsRequest request, final Headers headers,
        final String owner, final AuditContext ctx, final Throwable err
    ) {
        final Throwable cause = rootCause(err);
        if (cause instanceof UnsupportedOperationException) {
            throw new CompletionException(cause);
        }
        RequestContextHeaders.bindToMdc(headers);
        final boolean missing = cause instanceof DockerReferenceNotFoundException;
        AuditLogger.delete(
            ctx, REPO_TYPE, this.docker.registryName(), request.name(),
            request.digest().string(), owner, AuditLogger.OUTCOME_FAILURE,
            missing ? AuditLogger.REASON_NOT_FOUND : AuditLogger.REASON_STORAGE_UNAVAILABLE
        );
        if (!missing) {
            EcsLogger.error("com.auto1.pantera.docker")
                .message("Blob delete failed")
                .eventCategory("web")
                .eventAction("blob_delete")
                .eventOutcome("failure")
                .field("repository.name", this.docker.registryName())
                .field("container.image.name", request.name())
                .field("package.checksum", request.digest().string())
                .error(cause)
                .field("log.source", "application")
                .log();
            throw new CompletionException(cause);
        }
        EcsLogger.warn("com.auto1.pantera.docker")
            .message("Blob delete failed: digest not found")
            .eventCategory("web")
            .eventAction("blob_delete")
            .eventOutcome("failure")
            .field("repository.name", this.docker.registryName())
            .field("container.image.name", request.name())
            .field("package.checksum", request.digest().string())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.notFound()
            .jsonBody(new BlobUnknownError(request.digest()).json())
            .build();
    }

    /**
     * Unwraps nested {@link CompletionException}/{@link
     * java.util.concurrent.ExecutionException} layers to find the
     * originating cause.
     *
     * @param ex Throwable to unwrap.
     * @return Root cause.
     */
    private static Throwable rootCause(final Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null
            && cause.getCause() != cause) { // NOPMD CompareObjectsWithEquals - intentional identity check (cycle guard for self-causing exception)
            cause = cause.getCause();
        }
        return cause;
    }
}
