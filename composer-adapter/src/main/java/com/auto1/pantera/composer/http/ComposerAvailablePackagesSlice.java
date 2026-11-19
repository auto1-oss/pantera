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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code GET /p2/available-packages.json} (WS4-composer.5) — advertised by
 * {@link com.auto1.pantera.composer.SatisLayout} for every generated root
 * but, until now, unrouted (404). Enumerates the package names published
 * into this repository via the shared {@link ArtifactIndex}, letting
 * {@code composer show -a}/wildcard resolution work against a local or
 * hosted {@code php} repository.
 *
 * @since 2.3.0
 */
public final class ComposerAvailablePackagesSlice implements Slice {

    private static final String REPO_TYPE = "php";

    /** See {@code ComposerListSlice} — bounded to a self-hosted catalog, not a full mirror. */
    private static final int MAX_RESULTS = 5_000;

    private final ArtifactIndex index;

    private final String repoName;

    /**
     * @param index Shared artifact index
     * @param repoName Repository name to scope results to
     */
    public ComposerAvailablePackagesSlice(final ArtifactIndex index, final String repoName) {
        this.index = Objects.requireNonNull(index, "index");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.index.search(
                "%", MAX_RESULTS, 0, REPO_TYPE, this.repoName, "name", true
            ).thenApply(result -> {
                final Set<String> names = new LinkedHashSet<>();
                for (final ArtifactDocument doc : result.documents()) {
                    if (doc.artifactName() != null) {
                        names.add(doc.artifactName());
                    }
                }
                final JsonArrayBuilder available = Json.createArrayBuilder();
                names.forEach(available::add);
                return ResponseBuilder.ok()
                    .jsonBody(Json.createObjectBuilder()
                        .add("available-packages", available)
                        .build())
                    .build();
            })
        );
    }
}
