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

import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * In-memory {@link ArtifactIndex} test double. Filters the fixed document set
 * by a case-insensitive substring match on the artifact name, mirroring
 * {@code DbArtifactIndex}'s search semantics closely enough to prove
 * {@link SearchSlice} wires query/size/from/repo scoping correctly — without
 * requiring a live database (unit test doctrine).
 */
final class FakeArtifactIndex implements ArtifactIndex {

    /**
     * Fixed document set searched against.
     */
    private final List<ArtifactDocument> docs;

    /**
     * Last query string passed to the filtered search overload, captured for
     * assertions.
     */
    String lastQuery;

    /**
     * Last repo type filter passed to the filtered search overload.
     */
    String lastRepoType;

    /**
     * Last repo name filter passed to the filtered search overload.
     */
    String lastRepoName;

    /**
     * Ctor.
     * @param docs Fixed document set to search against
     */
    FakeArtifactIndex(final List<ArtifactDocument> docs) {
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
        return this.search(query, maxResults, offset, null, null, null, true);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName, final String sortBy, final boolean sortAsc
    ) {
        this.lastQuery = query;
        this.lastRepoType = repoType;
        this.lastRepoName = repoName;
        final String needle = query.toLowerCase(Locale.ROOT);
        final List<ArtifactDocument> matched = this.docs.stream()
            .filter(doc -> doc.artifactName().toLowerCase(Locale.ROOT).contains(needle))
            .collect(Collectors.toList());
        final List<ArtifactDocument> page = matched.stream()
            .skip(offset)
            .limit(maxResults)
            .collect(Collectors.toList());
        return CompletableFuture.completedFuture(
            new SearchResult(page, matched.size(), offset)
        );
    }

    @Override
    public CompletableFuture<List<String>> locate(final String artifactPath) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public void close() {
        // no resources to release
    }
}
