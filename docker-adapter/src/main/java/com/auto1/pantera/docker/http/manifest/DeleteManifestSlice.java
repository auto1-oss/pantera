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
import com.auto1.pantera.docker.error.DockerReferenceNotFoundException;
import com.auto1.pantera.docker.error.ManifestError;
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
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.security.Permission;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@code DELETE /v2/<name>/manifests/<reference>} — OCI/Distribution manifest
 * delete (image deletion / GC / {@code skopeo delete}).
 *
 * <p>A tag delete removes only that tag (the manifest stays pullable by
 * digest); a digest delete removes the manifest's by-digest link and every
 * tag pointing at it — see {@link com.auto1.pantera.docker.Manifests#delete}.
 * Every removed tag is dropped from the artifact search index. Never cascades
 * into deleting the underlying blob (separate op: {@code DELETE
 * .../blobs/<digest>}, {@link com.auto1.pantera.docker.http.blobs.DeleteBlobSlice})
 * since content-addressed blobs may be shared by more than one manifest.
 *
 * <p>Hosted ({@code docker}) repositories only: a {@code docker-proxy}
 * answers {@code 405 UNSUPPORTED} before reaching this slice, and the
 * proxy/composite {@code Manifests} implementations reject delete with
 * {@link UnsupportedOperationException}, mapped by {@code ErrorHandlingSlice}
 * to {@code 405} — deletes target the authoritative store only.
 */
public final class DeleteManifestSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail — this slice is wired
     * only for {@code docker} (hosted) repositories.
     */
    private static final String REPO_TYPE = "docker";

    /**
     * Artifact events queue (search-index updates), {@code null} when the
     * repository has none.
     */
    private final Queue<ArtifactEvent> events;

    /**
     * Ctor without search-index updates.
     *
     * @param docker Docker repository.
     */
    public DeleteManifestSlice(final Docker docker) {
        this(docker, null);
    }

    /**
     * Ctor.
     *
     * @param docker Docker repository.
     * @param events Artifact events queue, may be {@code null}.
     */
    public DeleteManifestSlice(final Docker docker, final Queue<ArtifactEvent> events) {
        super(docker);
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final ManifestRequest request = ManifestRequest.from(line);
        final String owner = new Login(headers).getValue();
        // Captured at slice entry, before any async hop.
        final AuditContext ctx = new AuditContext(headers);
        return body.asBytesFuture().thenCompose(
            ignored -> this.docker.repo(request.name()).manifests().delete(request.reference())
        ).<Response>thenApply(
            tags -> this.deleted(request, headers, owner, ctx, tags)
        ).exceptionally(
            err -> this.failed(request, headers, owner, ctx, err)
        );
    }

    @Override
    public Permission permission(final RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.DELETE.mask()
        );
    }

    /**
     * Success path: audit, de-index every removed tag, answer 202.
     *
     * @param request Manifest request.
     * @param headers Request headers.
     * @param owner Requesting user.
     * @param ctx Audit context.
     * @param tags Tags removed by the delete.
     * @return 202 Accepted.
     */
    private Response deleted(
        final ManifestRequest request, final Headers headers,
        final String owner, final AuditContext ctx, final Collection<String> tags
    ) {
        RequestContextHeaders.bindToMdc(headers);
        final String reference = request.reference().digest();
        AuditLogger.delete(
            ctx, REPO_TYPE, this.docker.registryName(), request.name(),
            reference, owner, AuditLogger.OUTCOME_SUCCESS, null
        );
        if (this.events != null) {
            // Tags are what the search index holds (see PushManifestSlice).
            for (final String tag : tags) {
                this.events.add(
                    new ArtifactEvent(
                        REPO_TYPE, this.docker.registryName(), request.name(), tag
                    ).withRequestContext(headers)
                );
            }
        }
        EcsLogger.info("com.auto1.pantera.docker")
            .message("Manifest deleted")
            .eventCategory("web")
            .eventAction("manifest_delete")
            .eventOutcome("success")
            .field("repository.name", this.docker.registryName())
            .field("user.name", owner)
            .field("container.image.name", request.name())
            .field("container.image.tag", reference)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.accepted().build();
    }

    /**
     * Failure path: 404 MANIFEST_UNKNOWN for an unknown reference; any other
     * failure (unsupported on proxy/composite, storage error) propagates to
     * {@code ErrorHandlingSlice}.
     *
     * @param request Manifest request.
     * @param headers Request headers.
     * @param owner Requesting user.
     * @param ctx Audit context.
     * @param err Failure.
     * @return 404 response.
     */
    private Response failed(
        final ManifestRequest request, final Headers headers,
        final String owner, final AuditContext ctx, final Throwable err
    ) {
        final Throwable cause = rootCause(err);
        if (cause instanceof UnsupportedOperationException) {
            throw new CompletionException(cause);
        }
        RequestContextHeaders.bindToMdc(headers);
        final String reference = request.reference().digest();
        final boolean missing = cause instanceof DockerReferenceNotFoundException;
        AuditLogger.delete(
            ctx, REPO_TYPE, this.docker.registryName(), request.name(),
            reference, owner, AuditLogger.OUTCOME_FAILURE,
            missing ? AuditLogger.REASON_NOT_FOUND : AuditLogger.REASON_STORAGE_UNAVAILABLE
        );
        if (!missing) {
            EcsLogger.error("com.auto1.pantera.docker")
                .message("Manifest delete failed")
                .eventCategory("web")
                .eventAction("manifest_delete")
                .eventOutcome("failure")
                .field("repository.name", this.docker.registryName())
                .field("container.image.name", request.name())
                .field("container.image.tag", reference)
                .error(cause)
                .field("log.source", "application")
                .log();
            throw new CompletionException(cause);
        }
        EcsLogger.warn("com.auto1.pantera.docker")
            .message("Manifest delete failed: reference not found")
            .eventCategory("web")
            .eventAction("manifest_delete")
            .eventOutcome("failure")
            .field("repository.name", this.docker.registryName())
            .field("container.image.name", request.name())
            .field("container.image.tag", reference)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.notFound()
            .jsonBody(new ManifestError(request.reference()).json())
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
