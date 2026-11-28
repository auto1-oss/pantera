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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code GET /packages/list.json} (WS4-composer.6) — backs both the {@code list}
 * and {@code search} top-level root fields rewritten to this path by
 * {@link com.auto1.pantera.composer.http.proxy.MetadataUrlRewriter#rewriteRoot}
 * (both point here; Composer's {@code search} query shape carries {@code ?q=}).
 *
 * <p>Sourced from the shared {@link ArtifactIndex} — the same index every
 * Composer publish already writes to via {@code SyncArtifactIndexer}
 * ({@link AddArchiveSlice}) — so results reflect exactly what has been
 * published into this repository, permission-filtered the same way as
 * every other search surface.
 *
 * @since 2.3.0
 */
public final class ComposerListSlice implements Slice {

    /**
     * Base repo type filter — matches {@code php}, {@code php-proxy},
     * {@code php-group}.
     */
    private static final String REPO_TYPE = "php";

    /**
     * Upper bound on enumerated packages. This repository's own catalog,
     * not a full Packagist mirror — a self-hosted {@code php}/{@code
     * php-proxy}/{@code php-group} repository realistically holds a few
     * thousand distinct packages at most.
     */
    private static final int MAX_RESULTS = 5_000;

    /**
     * Query-string {@code q} parameter pattern (Composer's rewritten
     * {@code search} URL shape is {@code ?q=%query%&type=%type%}).
     */
    private static final Pattern QUERY_PARAM = Pattern.compile("(?:^|&)q=([^&]*)");

    private final ArtifactIndex index;

    private final String repoName;

    /**
     * @param index Shared artifact index
     * @param repoName Repository name to scope results to
     */
    public ComposerListSlice(final ArtifactIndex index, final String repoName) {
        this.index = Objects.requireNonNull(index, "index");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            final String term = queryTerm(line.uri().getQuery());
            return this.index.search(
                term, MAX_RESULTS, 0, REPO_TYPE, this.repoName, "name", true
            ).thenApply(result -> {
                final Set<String> names = new LinkedHashSet<>();
                for (final ArtifactDocument doc : result.documents()) {
                    if (doc.artifactName() != null) {
                        names.add(doc.artifactName());
                    }
                }
                final JsonArrayBuilder packageNames = Json.createArrayBuilder();
                names.forEach(packageNames::add);
                return ResponseBuilder.ok()
                    .jsonBody(Json.createObjectBuilder()
                        .add("packageNames", packageNames)
                        .build())
                    .build();
            });
        });
    }

    /**
     * Extract the {@code q} query param, if any; a blank/absent term maps
     * to a match-all LIKE pattern so {@code list.json} (no query) enumerates
     * every published package, while {@code search.json?q=...} narrows to
     * the term.
     */
    private static String queryTerm(final String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "%";
        }
        final Matcher matcher = QUERY_PARAM.matcher(rawQuery);
        if (matcher.find()) {
            final String value = matcher.group(1);
            return value == null || value.isBlank() ? "%" : value;
        }
        return "%";
    }
}
