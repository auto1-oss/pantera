/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.index;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
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
     * Full-text search across all indexed artifacts.
     *
     * @param query Search query string
     * @param maxResults Maximum results to return
     * @param offset Starting offset for pagination
     * @return Search result with matching documents
     */
    CompletableFuture<SearchResult> search(String query, int maxResults, int offset);

    /**
     * Locate which repositories contain a given artifact path.
     * This is the critical operation for O(1) group lookup.
     *
     * @param artifactPath Artifact path to locate
     * @return List of repository names containing this artifact
     */
    CompletableFuture<List<String>> locate(String artifactPath);

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
