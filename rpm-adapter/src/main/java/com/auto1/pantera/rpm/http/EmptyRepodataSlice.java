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
                        EcsLogger.info("com.auto1.pantera.rpm")
                            .message("Generating empty repository metadata")
                            .eventCategory("file")
                            .eventAction("rpm_metadata_init")
                            .eventOutcome("success")
                            .field("repository.name", this.config.name())
                            .log();
                        res = new AstoRepoAdd(this.asto, this.config).perform();
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
}
