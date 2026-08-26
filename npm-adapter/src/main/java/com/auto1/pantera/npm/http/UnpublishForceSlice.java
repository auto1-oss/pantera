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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.npm.misc.PackumentRevision;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm unpublish` command requests.
 * Request line to this slice looks like `/[<@scope>/]pkg/-rev/&lt;revision&gt;`.
 * The revision is validated against {@link PackumentRevision} before anything is
 * deleted: a match deletes the package and answers 200, a parseable-but-stale
 * revision answers 409, and an absent, literal {@code undefined}, or malformed
 * revision answers 428 -- npm clients driven by {@code libnpmpublish} always read
 * the packument before unpublishing, so they send a real revision; a hand-rolled
 * request that skips that step now gets rejected instead of silently deleting the
 * package.
 */
final class UnpublishForceSlice implements Slice {
    /**
     * Endpoint request line pattern.
     */
    static final Pattern PTRN = Pattern.compile("/.*/-rev/.*$");

    /**
     * Abstract Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param storage Abstract storage
     * @param events Events queue
     * @param rname Repository name
     */
    UnpublishForceSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String uri = line.uri().getPath();
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(
            ignored -> {
                final CompletableFuture<Response> result;
                if (UnpublishForceSlice.PTRN.matcher(uri).matches()) {
                    final int marker = uri.indexOf("/-rev/");
                    final String pkg = new PackageNameFromUrl(
                        String.format(
                            "%s %s %s", line.method(), uri.substring(0, marker), line.version()
                        )
                    ).value();
                    final String sent = uri.substring(marker + "/-rev/".length());
                    result = this.deleteIfCurrent(pkg, sent);
                } else {
                    result = ResponseBuilder.badRequest().completedFuture();
                }
                return result;
            }
        );
    }

    /**
     * Delete the package only when the supplied revision is current.
     * @param pkg Package name
     * @param sent Revision supplied by the client
     * @return Response
     */
    private CompletableFuture<Response> deleteIfCurrent(final String pkg, final String sent) {
        return this.storage.list(new Key.From(pkg)).thenCompose(
            keys -> {
                final CompletableFuture<Response> result;
                if (keys.isEmpty()) {
                    result = ResponseBuilder.notFound().completedFuture();
                } else if (sent.isEmpty() || "undefined".equals(sent) || sent.indexOf('-') < 1) {
                    result = ResponseBuilder.from(RsStatus.PRECONDITION_REQUIRED)
                        .header("X-Pantera-Reason", "revision_required")
                        .completedFuture();
                } else {
                    result = new PackumentRevision(this.storage, pkg).value().thenCompose(
                        current -> {
                            final CompletableFuture<Response> answer;
                            if (current.equals(sent)) {
                                answer = this.purge(pkg);
                            } else {
                                answer = ResponseBuilder.from(RsStatus.CONFLICT)
                                    .header("X-Pantera-Reason", "revision_mismatch")
                                    .completedFuture();
                            }
                            return answer;
                        }
                    );
                }
                return result;
            }
        );
    }

    /**
     * Delete the package and emit the artifact event.
     * @param pkg Package name
     * @return Response
     */
    private CompletableFuture<Response> purge(final String pkg) {
        CompletableFuture<Void> res = this.storage.deleteAll(new Key.From(pkg));
        if (this.events.isPresent()) {
            res = res.thenRun(
                () -> this.events.map(
                    queue -> queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
                        new ArtifactEvent(UploadSlice.REPO_TYPE, this.rname, pkg)
                    )
                )
            );
        }
        return res.thenApply(nothing -> ResponseBuilder.ok().build());
    }
}
