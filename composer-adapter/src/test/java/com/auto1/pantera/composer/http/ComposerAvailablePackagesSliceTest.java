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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ComposerAvailablePackagesSlice} (WS4-composer.5).
 */
final class ComposerAvailablePackagesSliceTest {

    @Test
    void listsPublishedPackageNamesUnderAvailablePackagesKey() {
        final List<ArtifactDocument> docs = List.of(
            new ArtifactDocument("php", "php-repo", "vendor/a/1.0.0.zip", "vendor/a", "1.0.0", 1L, Instant.now(), "t"),
            new ArtifactDocument("php", "php-repo", "vendor/b/2.0.0.zip", "vendor/b", "2.0.0", 1L, Instant.now(), "t")
        );
        final ComposerAvailablePackagesSlice slice = new ComposerAvailablePackagesSlice(
            new FixedArtifactIndex(docs), "php-repo"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/p2/available-packages.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        final JsonObject body = Json.createReader(
            new java.io.ByteArrayInputStream(response.body().asBytesFuture().join())
        ).readObject();
        final JsonArray available = body.getJsonArray("available-packages");
        MatcherAssert.assertThat(
            "both published packages are listed",
            available.size(),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "first package name preserved",
            available.getString(0),
            new IsEqual<>("vendor/a")
        );
    }

    @Test
    void emptyIndexYieldsEmptyArrayNot404() {
        final ComposerAvailablePackagesSlice slice = new ComposerAvailablePackagesSlice(
            new FixedArtifactIndex(Collections.emptyList()), "php-repo"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/p2/available-packages.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        final JsonObject body = Json.createReader(
            new java.io.ByteArrayInputStream(response.body().asBytesFuture().join())
        ).readObject();
        MatcherAssert.assertThat(
            "empty repo still returns a 200 with an empty array",
            body.getJsonArray("available-packages").size(),
            new IsEqual<>(0)
        );
    }

    /**
     * Fake index returning a fixed document list regardless of query.
     */
    private static final class FixedArtifactIndex implements ArtifactIndex {

        private final List<ArtifactDocument> docs;

        FixedArtifactIndex(final List<ArtifactDocument> docs) {
            this.docs = docs;
        }

        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(final String repoName, final String artifactPath) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int maxResults, final int offset
        ) {
            return this.search(query, maxResults, offset, null, null, "name", true);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int maxResults, final int offset,
            final String repoType, final String repoName, final String sortBy, final boolean sortAsc
        ) {
            return CompletableFuture.completedFuture(
                new SearchResult(this.docs, this.docs.size(), offset, null, Map.of(), Map.of())
            );
        }

        @Override
        public CompletableFuture<List<String>> locate(final String artifactPath) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
