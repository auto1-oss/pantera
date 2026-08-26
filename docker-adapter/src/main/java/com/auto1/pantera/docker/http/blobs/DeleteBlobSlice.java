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
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import org.slf4j.MDC;

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
 * <p>Hosted ({@code docker}) repositories only: {@code docker-proxy}/{@code
 * docker-group} composites reject with {@link UnsupportedOperationException},
 * mapped by {@code ErrorHandlingSlice} to {@code 405 Method Not Allowed} —
 * deletes target the authoritative store only (WS4-docker.5 §3).
 */
public final class DeleteBlobSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail — this slice is wired
     * only for {@code docker} (hosted) repositories.
     */
    private static final String REPO_TYPE = "docker";

    public DeleteBlobSlice(final Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final BlobsRequest request = BlobsRequest.from(line);
        final String owner = new Login(headers).getValue();
        // Captured before the async hop — MDC does not survive worker-thread
        // continuations (CLAUDE.md audit rules: captureAuditContext before
        // any async hop).
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        return body.asBytesFuture().thenCompose(
            ignored -> this.docker.repo(request.name()).layers().delete(request.digest())
        ).<Response>thenApply(nothing -> {
            AuditLogger.delete(
                ctx, REPO_TYPE, this.docker.registryName(), request.name(),
                request.digest().string(), owner, AuditLogger.OUTCOME_SUCCESS, null
            );
            EcsLogger.info("com.auto1.pantera.docker")
                .message("Blob deleted")
                .eventCategory("web")
                .eventAction("blob_delete")
                .eventOutcome("success")
                .field("container.image.name", request.name())
                .field("package.checksum", request.digest().string())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.accepted().build();
        }).exceptionally(err -> {
            final Throwable cause = rootCause(err);
            if (cause instanceof UnsupportedOperationException) {
                // docker-proxy / docker-group: rethrow so ErrorHandlingSlice
                // maps it to 405, exactly like PUT does today for proxy repos.
                throw new CompletionException(cause);
            }
            AuditLogger.delete(
                ctx, REPO_TYPE, this.docker.registryName(), request.name(),
                request.digest().string(), owner,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
            );
            EcsLogger.warn("com.auto1.pantera.docker")
                .message("Blob delete failed: digest not found")
                .eventCategory("web")
                .eventAction("blob_delete")
                .eventOutcome("failure")
                .field("container.image.name", request.name())
                .field("package.checksum", request.digest().string())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.notFound()
                .jsonBody(new BlobUnknownError(request.digest()).json())
                .build();
        });
    }

    @Override
    public Permission permission(final RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.DELETE.mask()
        );
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
