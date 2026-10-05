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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.RepoPathRemoval;
import java.util.concurrent.CompletableFuture;

/**
 * {@code DELETE /<repo>/<path>} of a hosted repository (JFrog parity): a
 * file path deletes the file, a directory path deletes its subtree (never
 * siblings that merely share the name as a string prefix), and the delete
 * cascades into the search index, the tree view and the format's own
 * metadata exactly like {@code DELETE /api/v1/repositories/:name/artifacts}
 * -- both go through {@link ArtifactDeletion}.
 *
 * <p>Runs inside {@code TrimPathSlice}: the request path is the
 * repository-relative storage path. Authorization (the repository's
 * {@code delete} permission) is the wrapping auth slice's. Answers
 * {@code 204} when something was stored or indexed at the path,
 * {@code 404} otherwise, {@code 400} for an unsafe path or the repository
 * root. Every outcome is audited as {@code artifact_delete}.</p>
 *
 * @since 2.2.10
 */
public final class RepoDeleteSlice implements Slice {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.http.slice";

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Repository type.
     */
    private final String type;

    /**
     * Repository storage (repository-relative keys).
     */
    private final Storage storage;

    /**
     * Shared artifact delete.
     */
    private final ArtifactDeletion deletion;

    /**
     * Ctor.
     * @param repo Repository name
     * @param type Repository type
     * @param storage Repository storage (repository-relative keys)
     * @param deletion Shared artifact delete
     */
    public RepoDeleteSlice(
        final String repo, final String type, final Storage storage,
        final ArtifactDeletion deletion
    ) {
        this.repo = repo;
        this.type = type;
        this.storage = storage;
        this.deletion = deletion;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        // Captured at entry from the request's X-Pantera-Ctx-* headers: the
        // storage continuations may run on a worker whose MDC belongs to
        // another request.
        final AuditContext audit = new AuditContext(headers);
        final String owner = new Login(headers).getValue();
        final String path = RepoDeleteSlice.clean(line.uri().getPath());
        return body.asBytesFuture().thenCompose(
            ignored -> {
                final CompletableFuture<Response> res;
                if (path.isEmpty()) {
                    res = CompletableFuture.completedFuture(
                        ResponseBuilder.badRequest()
                            .textBody("Refusing to delete the repository root")
                            .build()
                    );
                } else if (this.deletion.unsafe(path) || path.contains("//")) {
                    res = CompletableFuture.completedFuture(
                        ResponseBuilder.badRequest().textBody("Invalid path").build()
                    );
                } else {
                    res = this.delete(path, audit, owner);
                }
                return res;
            }
        );
    }

    /**
     * Delete and audit.
     * @param path Repository-relative path
     * @param audit Request context captured at entry
     * @param owner Authenticated user
     * @return Response
     */
    private CompletableFuture<Response> delete(
        final String path, final AuditContext audit, final String owner
    ) {
        return this.deletion.delete(
            this.repo, this.type, this.storage, path, RepoPathRemoval.Mode.AUTO
        ).handle(
            (found, err) -> {
                final Response rsp;
                if (err == null) {
                    AuditLogger.delete(
                        audit, this.type, this.repo, path, null, owner,
                        found ? AuditLogger.OUTCOME_SUCCESS : AuditLogger.OUTCOME_FAILURE,
                        found ? null : AuditLogger.REASON_NOT_FOUND
                    );
                    if (found) {
                        rsp = ResponseBuilder.noContent().build();
                    } else {
                        rsp = ResponseBuilder.notFound()
                            .textBody("Nothing is stored or indexed at path: " + path)
                            .build();
                    }
                } else {
                    AuditLogger.delete(
                        audit, this.type, this.repo, path, null, owner,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_STORAGE_UNAVAILABLE
                    );
                    EcsLogger.error(RepoDeleteSlice.LOGGER)
                        .message("Repository path delete failed")
                        .eventCategory("file")
                        .eventAction("artifact_delete")
                        .eventOutcome("failure")
                        .field("repository.name", this.repo)
                        .field("file.path", path)
                        .error(err)
                        .field("log.source", "application")
                        .log();
                    rsp = ResponseBuilder.internalError().build();
                }
                return rsp;
            }
        );
    }

    /**
     * Repository-relative path without surrounding slashes.
     * @param raw Request path
     * @return Clean path, empty for the repository root
     */
    static String clean(final String raw) {
        String res = raw == null ? "" : raw;
        while (res.startsWith("/")) {
            res = res.substring(1);
        }
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
