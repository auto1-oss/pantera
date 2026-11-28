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
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ComposerListSlice} (WS4-composer.6).
 */
final class ComposerListSliceTest {

    @Test
    void mapsSearchResultToPackageNamesShapeAndDedupes() {
        final AtomicReference<String> capturedQuery = new AtomicReference<>();
        final ComposerListSlice slice = new ComposerListSlice(
            new FakeArtifactIndex(capturedQuery, docs("vendor/pkg-a", "vendor/pkg-a", "vendor/pkg-b")),
            "php-repo"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/packages/list.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        final JsonObject body = Json.createReader(
            new java.io.ByteArrayInputStream(response.body().asBytesFuture().join())
        ).readObject();
        final JsonArray names = body.getJsonArray("packageNames");
        MatcherAssert.assertThat(
            "no-query list.json is a match-all pattern",
            capturedQuery.get(),
            new IsEqual<>("%")
        );
        MatcherAssert.assertThat(
            "duplicate package name across versions collapses to one entry",
            names.size(),
            new IsEqual<>(2)
        );
    }

    @Test
    void forwardsQParamAsSearchTerm() {
        final AtomicReference<String> capturedQuery = new AtomicReference<>();
        final ComposerListSlice slice = new ComposerListSlice(
            new FakeArtifactIndex(capturedQuery, Collections.emptyList()), "php-repo"
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/packages/list.json?q=logger&type=library"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "q param forwarded as the search term",
            capturedQuery.get(),
            new IsEqual<>("logger")
        );
    }

    private static List<ArtifactDocument> docs(final String... names) {
        return java.util.Arrays.stream(names)
            .map(n -> new ArtifactDocument(
                "php", "php-repo", n + "/1.0.0.zip", n, "1.0.0", 10L, Instant.now(), "tester"
            ))
            .toList();
    }

    /**
     * Minimal fake index — captures the query term passed by
     * {@link ComposerListSlice} and returns the fixed document list.
     */
    private static final class FakeArtifactIndex implements ArtifactIndex {

        private final AtomicReference<String> capturedQuery;
        private final List<ArtifactDocument> docs;

        FakeArtifactIndex(final AtomicReference<String> capturedQuery, final List<ArtifactDocument> docs) {
            this.capturedQuery = capturedQuery;
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
            this.capturedQuery.set(query);
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
