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

import java.util.List;
import java.util.Map;

/**
 * Search result from the artifact index.
 *
 * @param documents Matching artifact documents
 * @param totalHits Total number of matching documents
 * @param offset Starting offset used for this result
 * @param lastScoreDoc Opaque cursor for searchAfter pagination;
 *  null when there are no more pages or when using offset-based pagination.
 *  Callers should treat this as an opaque token for cursor-based paging.
 * @param typeCounts Aggregation: base repo type to count (unfiltered by type/repo).
 *  Empty map when aggregation is not available.
 * @param repoCounts Aggregation: repo name to count (scoped to active type filter).
 *  Empty map when aggregation is not available.
 * @since 1.20.13
 */
public record SearchResult(
    List<ArtifactDocument> documents,
    long totalHits,
    int offset,
    Object lastScoreDoc,
    Map<String, Long> typeCounts,
    Map<String, Long> repoCounts
) {

    /**
     * Backward-compatible constructor without cursor or aggregations.
     *
     * @param documents Matching artifact documents
     * @param totalHits Total number of matching documents
     * @param offset Starting offset used for this result
     */
    public SearchResult(
        final List<ArtifactDocument> documents,
        final long totalHits,
        final int offset
    ) {
        this(documents, totalHits, offset, null, Map.of(), Map.of());
    }

    /**
     * Backward-compatible constructor without aggregations.
     *
     * @param documents Matching artifact documents
     * @param totalHits Total number of matching documents
     * @param offset Starting offset used for this result
     * @param lastScoreDoc Opaque cursor for searchAfter pagination
     */
    public SearchResult(
        final List<ArtifactDocument> documents,
        final long totalHits,
        final int offset,
        final Object lastScoreDoc
    ) {
        this(documents, totalHits, offset, lastScoreDoc, Map.of(), Map.of());
    }

    /**
     * Empty search result.
     */
    public static final SearchResult EMPTY =
        new SearchResult(List.of(), 0, 0, null, Map.of(), Map.of());
}
