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
package com.auto1.pantera.index;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Artifact search index interface.
 * Supports full-text search, exact path lookup, and artifact-to-repo location.
 *
 * @since 1.20.13
 */
public interface ArtifactIndex extends Closeable {

    /**
     * Index (upsert) an artifact document.
     * If a document with the same repoName+artifactPath exists, it is replaced.
     *
     * @param doc Artifact document to index
     * @return Future completing when indexed
     */
    CompletableFuture<Void> index(ArtifactDocument doc);

    /**
     * Remove an artifact from the index.
     *
     * @param repoName Repository name
     * @param artifactPath Artifact path
     * @return Future completing when removed
     */
    CompletableFuture<Void> remove(String repoName, String artifactPath);

    /**
     * Remove every artifact row whose {@code name} starts with the given
     * prefix. Used by the folder/package delete API to cascade the storage
     * delete into the DB index — without this, search returned ghosts for
     * files that had been removed from storage.
     *
     * <p>Default implementation returns the count as 0 without removing
     * anything; concrete indexes should override.</p>
     *
     * @param repoName Repository name (exact match)
     * @param pathPrefix Path prefix — rows where {@code name LIKE prefix%}
     *                   are deleted. Must not be empty (a whole-repo wipe
     *                   should go through the repo delete flow, not this).
     * @return Future carrying the number of rows removed
     */
    default CompletableFuture<Integer> removePrefix(
        final String repoName, final String pathPrefix
    ) {
        return CompletableFuture.completedFuture(0);
    }

    /**
     * Full-text search across all indexed artifacts.
     *
     * @param query Search query string
     * @param maxResults Maximum results to return
     * @param offset Starting offset for pagination
     * @return Search result with matching documents
     */
    CompletableFuture<SearchResult> search(String query, int maxResults, int offset);

    /**
     * Full-text search with optional server-side filtering and sorting.
     * Implementations that support filtering/sorting should override this.
     * The default falls back to the basic search.
     *
     * @param query Search query string
     * @param maxResults Maximum results to return
     * @param offset Starting offset for pagination
     * @param repoType Optional repo type base (e.g. "maven" matches maven, maven-proxy, maven-group)
     * @param repoName Optional exact repository name filter
     * @param sortBy Sort field: "relevance", "name", "version", "created_at"
     * @param sortAsc True for ascending, false for descending
     * @return Search result with matching documents
     */
    default CompletableFuture<SearchResult> search(
        String query, int maxResults, int offset,
        String repoType, String repoName, String sortBy, boolean sortAsc
    ) {
        return search(query, maxResults, offset);
    }

    /**
     * Locate which repositories contain a given artifact path.
     * Uses path_prefix matching — slower, used as fallback.
     *
     * @param artifactPath Artifact path to locate
     * @return List of repository names containing this artifact
     */
    CompletableFuture<List<String>> locate(String artifactPath);

    /**
     * Locate which repositories contain an artifact by its indexed name.
     * Uses the {@code name} column with B-tree index — O(log n), fast.
     * This is the primary operation for group lookup when the adapter type
     * is known and the name can be parsed from the URL.
     *
     * <p>The return value distinguishes two cases:
     * <ul>
     *   <li>{@code Optional.of(repos)} — successful query; repos may be empty (confirmed miss)</li>
     *   <li>{@code Optional.empty()} — DB error; caller should fall back to full fanout</li>
     * </ul>
     *
     * @param artifactName Artifact name as stored in the DB (adapter-specific format)
     * @return Future containing Optional: present=successful query, empty=DB error
     */
    default CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        return locate(artifactName).thenApply(Optional::of);
    }

    /**
     * Whether the index has completed its initial warmup scan.
     * @return true if warmup is complete and the index can be trusted
     */
    default boolean isWarmedUp() {
        return false;
    }

    /**
     * Mark the index as warmed up after initial scan completes.
     */
    default void setWarmedUp() {
        // no-op by default
    }

    /**
     * Get index statistics.
     * @return map of stat name to value
     */
    default CompletableFuture<Map<String, Object>> getStats() {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * Index a batch of documents efficiently (single commit).
     * Default implementation falls back to individual index() calls.
     *
     * @param docs Collection of documents to index
     * @return Future completing when batch is indexed
     */
    default CompletableFuture<Void> indexBatch(final java.util.Collection<ArtifactDocument> docs) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (final ArtifactDocument doc : docs) {
            result = result.thenCompose(v -> index(doc));
        }
        return result;
    }

    /**
     * No-op implementation that performs no indexing or searching.
     */
    ArtifactIndex NOP = new ArtifactIndex() {
        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(final String rn, final String ap) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Integer> removePrefix(final String rn, final String pref) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String q, final int max, final int off
        ) {
            return CompletableFuture.completedFuture(SearchResult.EMPTY);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String path) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public void close() {
            // nop
        }
    };
}
