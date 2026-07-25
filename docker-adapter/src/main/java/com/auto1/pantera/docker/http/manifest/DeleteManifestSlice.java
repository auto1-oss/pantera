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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.ManifestError;
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
 * {@code DELETE /v2/<name>/manifests/<reference>} — OCI/Distribution manifest
 * delete (image deletion / GC / {@code skopeo delete}).
 *
 * <p>Removes the requested tag/digest link, the canonical by-digest link,
 * and any OCI 1.1 referrers-index entry the manifest owned — see {@link
 * com.auto1.pantera.docker.Manifests#delete}. Never cascades into deleting
 * the underlying blob (separate op: {@code DELETE .../blobs/<digest>},
 * {@link DeleteBlobSlice}) since content-addressed blobs may be shared by
 * more than one manifest.
 *
 * <p>Hosted ({@code docker}) repositories only: {@code docker-proxy}/{@code
 * docker-group} composites reject with {@link UnsupportedOperationException},
 * mapped by {@code ErrorHandlingSlice} to {@code 405 Method Not Allowed} —
 * deletes target the authoritative store only (WS4-docker.5 §3).
 */
public final class DeleteManifestSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail — this slice is wired
     * only for {@code docker} (hosted) repositories.
     */
    private static final String REPO_TYPE = "docker";

    public DeleteManifestSlice(final Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final ManifestRequest request = ManifestRequest.from(line);
        final String owner = new Login(headers).getValue();
        // Captured before the async hop — MDC does not survive worker-thread
        // continuations (CLAUDE.md audit rules: captureAuditContext before
        // any async hop).
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        return body.asBytesFuture().thenCompose(
            ignored -> this.docker.repo(request.name()).manifests().delete(request.reference())
        ).<Response>thenApply(nothing -> {
            AuditLogger.delete(
                ctx, REPO_TYPE, this.docker.registryName(), request.name(),
                request.reference().digest(), owner, AuditLogger.OUTCOME_SUCCESS, null
            );
            EcsLogger.info("com.auto1.pantera.docker")
                .message("Manifest deleted")
                .eventCategory("web")
                .eventAction("manifest_delete")
                .eventOutcome("success")
                .field("container.image.name", request.name())
                .field("container.image.tag", request.reference().digest())
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
                request.reference().digest(), owner,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
            );
            EcsLogger.warn("com.auto1.pantera.docker")
                .message("Manifest delete failed: reference not found")
                .eventCategory("web")
                .eventAction("manifest_delete")
                .eventOutcome("failure")
                .field("container.image.name", request.name())
                .field("container.image.tag", request.reference().digest())
                .field("log.source", "application")
                .log();
            return ResponseBuilder.notFound()
                .jsonBody(new ManifestError(request.reference()).json())
                .build();
        });
    }

    @Override
    public Permission permission(final RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.DELETE.mask()
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
