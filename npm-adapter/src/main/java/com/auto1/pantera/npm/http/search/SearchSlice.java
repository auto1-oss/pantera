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
package com.auto1.pantera.npm.http.search;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Search slice - handles npm search.
 * Endpoint: GET /-/v1/search?text={query}&size={n}&from={offset}
 *
 * <p>Backed by the shared, already-populated {@link ArtifactIndex} (the same
 * index every publish writes to via {@code SyncArtifactIndexer}) instead of a
 * dedicated in-memory index that nothing ever populated.</p>
 *
 * @since 1.1
 */
public final class SearchSlice implements Slice {

    /**
     * Query parameter pattern.
     */
    private static final Pattern QUERY_PATTERN = Pattern.compile(
        "text=([^&]+)(?:&size=(\\d+))?(?:&from=(\\d+))?"
    );

    /**
     * Default result size.
     */
    private static final int DEFAULT_SIZE = 20;

    /**
     * Repo type base filter passed to the index (matches npm, npm-proxy, npm-group).
     */
    private static final String REPO_TYPE = "npm";

    /**
     * Artifact index shared with every other repository/format.
     */
    private final ArtifactIndex index;

    /**
     * This repository's name — scopes search results to packages published
     * into this repository, not every npm repository on the instance.
     */
    private final String repoName;

    /**
     * Constructor.
     * @param index Shared artifact index
     * @param repoName Repository name to scope results to
     */
    public SearchSlice(final ArtifactIndex index, final String repoName) {
        this.index = Objects.requireNonNull(index, "index");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            final String query = line.uri().getQuery();
            if (query == null || query.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .textBody("Search query required")
                        .build()
                );
            }

            final Matcher matcher = QUERY_PATTERN.matcher(query);
            if (!matcher.find()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .textBody("Invalid search query")
                        .build()
                );
            }

            final String text = matcher.group(1);
            final int size = matcher.group(2) != null
                ? Integer.parseInt(matcher.group(2))
                : DEFAULT_SIZE;
            final int from = matcher.group(3) != null
                ? Integer.parseInt(matcher.group(3))
                : 0;

            return this.index.search(
                text, size, from, SearchSlice.REPO_TYPE, this.repoName, "relevance", true
            ).thenApply(result -> {
                final JsonArrayBuilder objects = Json.createArrayBuilder();
                result.documents().forEach(doc -> objects.add(SearchSlice.packageToJson(doc)));

                return ResponseBuilder.ok()
                    .jsonBody(Json.createObjectBuilder()
                        .add("objects", objects)
                        .add("total", result.totalHits())
                        .add("time", System.currentTimeMillis())
                        .build())
                    .build();
            });
        });
    }

    /**
     * Convert an indexed artifact document to the npm search result schema.
     * @param doc Indexed artifact document
     * @return JSON object builder
     */
    private static JsonObjectBuilder packageToJson(final ArtifactDocument doc) {
        final String name = doc.artifactName() != null ? doc.artifactName() : doc.artifactPath();
        final String version = doc.version() != null ? doc.version() : "";
        return Json.createObjectBuilder()
            .add("package", Json.createObjectBuilder()
                .add("name", name)
                .add("version", version)
                .add("description", "")
                .add("keywords", Json.createArrayBuilder())
            )
            .add("score", Json.createObjectBuilder()
                .add("final", 1.0)
                .add("detail", Json.createObjectBuilder()
                    .add("quality", 1.0)
                    .add("popularity", 1.0)
                    .add("maintenance", 1.0)
                )
            )
            .add("searchScore", 1.0);
    }
}
