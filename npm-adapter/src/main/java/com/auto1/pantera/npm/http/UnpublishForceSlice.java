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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm unpublish` command requests. Two request shapes reach it,
 * both carrying the packument revision the client last read:
 * <ul>
 *   <li>{@code /[<@scope>/]pkg/-rev/<revision>} -- whole-package removal
 *   ({@code npm unpublish <pkg> --force});</li>
 *   <li>{@code /[<@scope>/]pkg/-/<tarball>.tgz/-rev/<revision>} -- the final leg of
 *   single-version removal ({@code npm unpublish <pkg>@<version>}): the client first
 *   PUTs the packument minus the version, re-reads the packument for its new
 *   revision, then deletes that version's tarball with it. Only the one blob is
 *   removed here; the version's index event was already emitted by the PUT.</li>
 * </ul>
 * In both shapes the revision is validated against the package's
 * {@link PackumentRevision} before anything is deleted: a match deletes and answers
 * 200, a parseable-but-stale revision answers 409, and an absent, literal
 * {@code undefined}, or malformed revision answers 428 -- npm clients driven by
 * {@code libnpmpublish} always read the packument before unpublishing, so they send
 * a real revision; a hand-rolled request that skips that step gets rejected instead
 * of silently deleting.
 */
final class UnpublishForceSlice implements Slice {
    /**
     * Endpoint request line pattern.
     */
    static final Pattern PTRN = Pattern.compile("/.*/-rev/.*$");

    /**
     * Path segment that precedes the revision.
     */
    private static final String REV_MARKER = "/-rev/";

    /**
     * Path segment that separates a package name from its tarball file name.
     */
    private static final String TARBALL_MARKER = "/-/";

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
                    final int marker = uri.indexOf(UnpublishForceSlice.REV_MARKER);
                    final String target = uri.substring(0, marker);
                    final String sent = uri.substring(
                        marker + UnpublishForceSlice.REV_MARKER.length()
                    );
                    final int tarball = target.indexOf(UnpublishForceSlice.TARBALL_MARKER);
                    if (tarball > 0) {
                        result = this.deleteTarball(
                            UnpublishForceSlice.name(line, target.substring(0, tarball)),
                            new Key.From(UnpublishForceSlice.name(line, target)),
                            sent
                        );
                    } else {
                        result = this.deletePackage(
                            UnpublishForceSlice.name(line, target), sent
                        );
                    }
                } else {
                    result = ResponseBuilder.badRequest().completedFuture();
                }
                return result;
            }
        );
    }

    /**
     * Delete the whole package only when the supplied revision is current.
     * @param pkg Package name
     * @param sent Revision supplied by the client
     * @return Response
     */
    private CompletableFuture<Response> deletePackage(final String pkg, final String sent) {
        return this.storage.list(new Key.From(pkg)).thenCompose(
            keys -> {
                final CompletableFuture<Response> result;
                if (keys.isEmpty()) {
                    result = ResponseBuilder.notFound().completedFuture();
                } else {
                    result = this.whenCurrent(pkg, sent, () -> this.purge(pkg));
                }
                return result;
            }
        );
    }

    /**
     * Delete a single version's tarball only when the supplied revision is
     * current for its package. Nothing else about the package is touched.
     * @param pkg Package name the tarball belongs to
     * @param tarball Storage key of the tarball
     * @param sent Revision supplied by the client
     * @return Response
     */
    private CompletableFuture<Response> deleteTarball(
        final String pkg, final Key tarball, final String sent
    ) {
        return this.storage.exists(tarball).thenCompose(
            exists -> {
                final CompletableFuture<Response> result;
                if (exists) {
                    result = this.whenCurrent(
                        pkg, sent,
                        () -> this.storage.delete(tarball)
                            .thenApply(nothing -> ResponseBuilder.ok().build())
                    );
                } else {
                    result = ResponseBuilder.notFound().completedFuture();
                }
                return result;
            }
        );
    }

    /**
     * Run the deletion only when the supplied revision matches the package's
     * current one; otherwise answer 428 (unusable revision) or 409 (stale).
     * @param pkg Package name whose revision is checked
     * @param sent Revision supplied by the client
     * @param action Deletion to run on a match
     * @return Response
     */
    private CompletableFuture<Response> whenCurrent(
        final String pkg, final String sent, final Supplier<CompletableFuture<Response>> action
    ) {
        final CompletableFuture<Response> result;
        if (sent.isEmpty() || "undefined".equals(sent) || sent.indexOf('-') < 1) {
            result = ResponseBuilder.from(RsStatus.PRECONDITION_REQUIRED)
                .header("X-Pantera-Reason", "revision_required")
                .completedFuture();
        } else {
            result = new PackumentRevision(this.storage, pkg).value().thenCompose(
                current -> {
                    final CompletableFuture<Response> answer;
                    if (current.equals(sent)) {
                        answer = action.get();
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

    /**
     * Package (or tarball) name from a request path, with the leading slash
     * stripped, using the same parser as the rest of the adapter.
     * @param line Request line the path came from (method and version reused)
     * @param path Path to parse
     * @return Name
     */
    private static String name(final RequestLine line, final String path) {
        return new PackageNameFromUrl(
            String.format("%s %s %s", line.method(), path, line.version())
        ).value();
    }
}
