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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.asto.AstoRepoRemove;
import com.auto1.pantera.rpm.meta.PackageInfo;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Rpm endpoint to remove a package: {@code DELETE /<file>.rpm}.
 *
 * <ul>
 *   <li>No such file: 404 Not Found (nothing is queued).</li>
 *   <li>An {@code X-Checksum-<ALG>} header is optional verification: when
 *   present and it does not match the stored file (or names an unknown
 *   algorithm), 400 Bad Request and nothing is removed. Without the header
 *   the file is removed; {@code ?force=true} is still accepted but no longer
 *   needed.</li>
 *   <li>Otherwise the file name is queued under {@link #TO_RM} and 202 Accepted
 *   is answered. In {@link RepoConfig.UpdateMode#UPLOAD} mode without
 *   {@code ?skip_update=true} the file is removed and the repodata rewritten
 *   before the response; otherwise the queued removal is applied by the next
 *   metadata update.</li>
 * </ul>
 */
public final class RpmRemove implements Slice {

    /**
     * Temp key for the packages to remove.
     */
    public static final Key TO_RM = new Key.From(".remove");

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Repository config.
     */
    private final RepoConfig cnfg;

    /**
     * Artifact upload/remove events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Ctor.
     * @param asto Asto storage
     * @param cnfg Repo config
     * @param events Artifact events
     */
    public RpmRemove(final Storage asto, final RepoConfig cnfg,
        final Optional<Queue<ArtifactEvent>> events) {
        this.asto = asto;
        this.cnfg = cnfg;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers,
        final Content body) {
        final RpmUpload.Request request = new RpmUpload.Request(line);
        final Key file = request.file();
        final Optional<Pair<String, String>> checksum = RpmRemove.checksum(headers);
        return body.discard().handle((ignored, err) -> null)
            .thenCompose(ignored -> this.asto.exists(file))
            .thenCompose(
                exists -> {
                    final CompletionStage<RsStatus> res;
                    if (exists) {
                        res = checksum.map(sum -> this.validate(file, sum))
                            .orElse(CompletableFuture.completedFuture(true))
                            .thenCompose(
                                valid -> {
                                    final CompletionStage<RsStatus> status;
                                    if (valid) {
                                        status = this.remove(request);
                                    } else {
                                        status = CompletableFuture.completedFuture(
                                            RsStatus.BAD_REQUEST
                                        );
                                    }
                                    return status;
                                }
                            );
                    } else {
                        res = CompletableFuture.completedFuture(RsStatus.NOT_FOUND);
                    }
                    return res;
                }
            ).thenApply(status -> ResponseBuilder.from(status).build());
    }

    /**
     * Queue the package for removal and, in upload mode, remove it and update
     * the repodata now.
     * @param request Request
     * @return 202 Accepted
     */
    private CompletionStage<RsStatus> remove(final RpmUpload.Request request) {
        return this.asto.save(new Key.From(RpmRemove.TO_RM, request.file()), Content.EMPTY)
            .thenCompose(
                nothing -> {
                    final CompletionStage<Void> res;
                    if (this.cnfg.mode() == RepoConfig.UpdateMode.UPLOAD
                        && !request.skipUpdate()) {
                        res = this.events.map(
                            queue -> {
                                final Collection<PackageInfo> infos = new ArrayList<>(1);
                                return new RepodataQueue(this.asto).run(
                                    new AstoRepoRemove(this.asto, this.cnfg, infos)::perform
                                ).thenAccept(
                                    ignored -> infos.forEach(
                                        item -> queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
                                            new ArtifactEvent(
                                                RpmUpload.REPO_TYPE,
                                                this.cnfg.name(), item.name(),
                                                item.version()
                                            )
                                        )
                                    )
                                );
                            }
                        ).orElseGet(
                            () -> new RepodataQueue(this.asto).run(
                                new AstoRepoRemove(this.asto, this.cnfg)::perform
                            )
                        );
                    } else {
                        res = CompletableFuture.completedFuture(null);
                    }
                    return res;
                }
            ).thenApply(ignored -> RsStatus.ACCEPTED);
    }

    /**
     * Validate rpm package to remove. Valid if:
     * a) package exists,
     * b) checksums (checksum of the existing package = checksum from request header) are equal.
     * @param file File key
     * @param checksum Accepted checksum to compare
     * @return True is package is valid
     */
    private CompletionStage<Boolean> validate(final Key file, final Pair<String, String> checksum) {
        return this.asto.exists(file).thenCompose(
            exists -> {
                CompletionStage<Boolean> res = CompletableFuture.completedFuture(false);
                if (exists) {
                    res = this.asto.value(file).thenCompose(
                        val -> new ContentDigest(
                            val, () -> new Digests.FromString(checksum.getKey()).get().get()
                        ).hex().thenApply(pkg -> pkg.equalsIgnoreCase(checksum.getValue()))
                    ).handle(
                        // An unknown algorithm cannot verify the file: refuse.
                        (same, err) -> err == null && same
                    );
                }
                return res;
            }
        );
    }

    /**
     * Obtain algorithm and checksum from headers.
     * @param headers Headers
     * @return Pair of algorithm and checksum if header was found
     */
    private static Optional<Pair<String, String>> checksum(Headers headers) {
        final String name = "x-checksum-";
        return headers.stream()
            .map(hdr -> new ImmutablePair<>(hdr.getKey().toLowerCase(Locale.US), hdr.getValue()))
            .filter(hdr -> hdr.getKey().startsWith(name))
            .findFirst().map(
                hdr -> new ImmutablePair<>(hdr.getKey().substring(name.length()), hdr.getValue())
            );
    }

}
