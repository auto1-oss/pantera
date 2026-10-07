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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release revert: {@code DELETE /packages/<name>/releases/<version>}, what
 * {@code mix hex.publish --revert <version>} sends to {@code HEX_API_URL}
 * (also accepted under {@code /api} and {@code /repos/<org>}, like publish).
 *
 * <p>Removes the release tarball {@code tarballs/<name>-<version>.tar} and,
 * through {@link ReleasesPruner}, the release from the package's registry
 * record {@code packages/<name>} (the record is removed with its last
 * release). Answers 204 No Content, or 404 when no such release is stored.
 * Every outcome is audited as {@code artifact_delete}; a successful revert
 * also publishes a delete-version artifact event so the search index row
 * goes.</p>
 *
 * @since 2.2.10
 */
public final class ReleaseDeleteSlice implements Slice {

    /**
     * Release path.
     */
    static final Pattern PATH = Pattern.compile(
        "(?:/api)?(?:/repos/[^/]+)?/packages/(?<name>[a-z][a-z0-9_]*)"
            + "/releases/(?<version>[0-9A-Za-z][0-9A-Za-z.+-]*)/?"
    );

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "hexpm";

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param storage Repository storage
     * @param events Artifact events
     * @param rname Repository name
     */
    public ReleaseDeleteSlice(
        final Storage storage, final Optional<Queue<ArtifactEvent>> events, final String rname
    ) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(headers);
        final String owner = new Login(headers).getValue();
        final Matcher matcher = ReleaseDeleteSlice.PATH.matcher(line.uri().getPath());
        return body.discard().thenCompose(
            ignored -> {
                final CompletableFuture<Response> res;
                if (matcher.matches() && !matcher.group("version").contains("..")) {
                    res = this.delete(
                        ctx, owner, headers, matcher.group("name"), matcher.group("version")
                    );
                } else {
                    AuditLogger.delete(
                        ctx, ReleaseDeleteSlice.REPO_TYPE, this.rname, line.uri().getPath(),
                        null, owner, AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                    );
                    res = ResponseBuilder.notFound().completedFuture();
                }
                return res;
            }
        );
    }

    /**
     * Delete a release.
     * @param ctx Audit context
     * @param owner Requesting user
     * @param headers Request headers
     * @param name Package name
     * @param version Release version
     * @return 204, or 404 when the release is not stored
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> delete(
        final AuditContext ctx, final String owner, final Headers headers,
        final String name, final String version
    ) {
        final Key tarball = new Key.From(
            DownloadSlice.TARBALLS, String.format("%s-%s.tar", name, version)
        );
        // The registry lock publish takes: a concurrent publish of the same
        // release cannot interleave with the tarball removal.
        return new IndexUpdateLock(this.storage, new Key.From(DownloadSlice.PACKAGES, name)).run(
            locked -> locked.exists(tarball).thenCompose(
                exists -> {
                    final CompletableFuture<Boolean> deleted;
                    if (exists) {
                        deleted = locked.delete(tarball).thenApply(nothing -> true);
                    } else {
                        deleted = CompletableFuture.completedFuture(false);
                    }
                    return deleted;
                }
            )
        ).thenCompose(
            deleted -> {
                final CompletableFuture<Response> res;
                if (deleted) {
                    res = new ReleasesPruner(this.storage).afterDelete(tarball.string())
                        .thenApply(
                            pruned -> {
                                RequestContextHeaders.bindToMdc(headers);
                                this.events.ifPresent(
                                    queue -> queue.add(
                                        new ArtifactEvent(
                                            ReleaseDeleteSlice.REPO_TYPE, this.rname,
                                            name, version
                                        )
                                    )
                                );
                                AuditLogger.delete(
                                    ctx, ReleaseDeleteSlice.REPO_TYPE, this.rname, name,
                                    version, owner, AuditLogger.OUTCOME_SUCCESS, null
                                );
                                return ResponseBuilder.noContent().build();
                            }
                        );
                } else {
                    RequestContextHeaders.bindToMdc(headers);
                    AuditLogger.delete(
                        ctx, ReleaseDeleteSlice.REPO_TYPE, this.rname, name, version, owner,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                    );
                    res = ResponseBuilder.notFound().completedFuture();
                }
                return res;
            }
        );
    }
}
