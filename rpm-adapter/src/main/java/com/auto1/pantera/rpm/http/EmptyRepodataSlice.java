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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.asto.AstoRepoAdd;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Generates the (empty) repository metadata of a repository that has none
 * yet when a client asks for {@code repodata/repomd.xml}: dnf refuses a
 * repository without it, so a new repository was unusable until the first
 * upload.
 *
 * <p>Generation runs in the repository's {@link RepodataQueue}, which
 * uploads and deletes also go through, and re-checks for metadata there, so
 * it never replaces metadata an upload wrote and never contends with an
 * upload or another read for the {@code repodata/} storage lock.</p>
 *
 * @since 2.2.9
 */
final class EmptyRepodataSlice implements Slice {

    /**
     * Repository metadata index.
     */
    private static final Key REPOMD = new Key.From("repodata", "repomd.xml");

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Storage.
     */
    private final Storage asto;

    /**
     * Repository config.
     */
    private final RepoConfig config;

    /**
     * Ctor.
     * @param origin Origin slice
     * @param asto Storage
     * @param config Repository config
     */
    EmptyRepodataSlice(final Slice origin, final Storage asto, final RepoConfig config) {
        this.origin = origin;
        this.asto = asto;
        this.config = config;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletionStage<Void> ready;
        if (("/" + EmptyRepodataSlice.REPOMD.string()).equals(line.uri().getPath())) {
            ready = this.asto.exists(EmptyRepodataSlice.REPOMD).thenCompose(
                exists -> {
                    final CompletionStage<Void> res;
                    if (exists) {
                        res = CompletableFuture.allOf();
                    } else {
                        res = new RepodataQueue(this.asto).run(this::initialise)
                            .handle(this::logged);
                    }
                    return res;
                }
            );
        } else {
            ready = CompletableFuture.allOf();
        }
        return ready.toCompletableFuture()
            .thenCompose(nothing -> this.origin.response(line, headers, body));
    }

    /**
     * Generate empty metadata unless an upload or another read wrote
     * metadata while this read waited for the queue. Runs in the
     * repository's {@link RepodataQueue}.
     * @return Whether this call generated the metadata
     */
    private CompletionStage<Boolean> initialise() {
        return this.asto.exists(EmptyRepodataSlice.REPOMD).thenCompose(
            exists -> {
                final CompletionStage<Boolean> res;
                if (exists) {
                    res = CompletableFuture.completedFuture(false);
                } else {
                    res = new AstoRepoAdd(this.asto, this.config).performEmpty()
                        .thenApply(nothing -> true);
                }
                return res;
            }
        );
    }

    /**
     * Log the outcome. A failure is not answered with an error: another node
     * sharing the storage may hold the metadata lock and be writing the
     * metadata, so the read serves whatever is stored now (the metadata that
     * node wrote, or 404 while there is none).
     * @param created Whether empty metadata was generated
     * @param err Error, or null
     * @return Nothing
     */
    private Void logged(final Boolean created, final Throwable err) {
        if (err != null) {
            EcsLogger.warn("com.auto1.pantera.rpm")
                .message("Could not generate empty repository metadata; serving what is stored")
                .eventCategory("file")
                .eventAction("rpm_metadata_init")
                .eventOutcome("failure")
                .field("repository.name", this.config.name())
                .field("log.source", "application")
                .error(err)
                .log();
        } else if (created) {
            EcsLogger.info("com.auto1.pantera.rpm")
                .message("Generated empty repository metadata")
                .eventCategory("file")
                .eventAction("rpm_metadata_init")
                .eventOutcome("success")
                .field("repository.name", this.config.name())
                .field("log.source", "application")
                .log();
        }
        return null;
    }
}
