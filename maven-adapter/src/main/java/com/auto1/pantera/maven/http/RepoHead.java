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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Head repository metadata.
 */
final class RepoHead {

    /**
     * Client slice.
     */
    private final Slice client;

    /**
     * New repository artifact's heads.
     * @param client Client slice
     */
    RepoHead(final Slice client) {
        this.client = client;
    }

    /**
     * Artifact head.
     * @param path Path for artifact
     * @return Artifact headers
     */
    CompletionStage<Optional<Headers>> head(final String path) {
        return this.client.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).thenApply(resp -> resp.status() == RsStatus.OK ? Optional.of(resp.headers()) : Optional.empty());
    }
}
