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

/**
 * Search result from the artifact index.
 *
 * @param documents Matching artifact documents
 * @param totalHits Total number of matching documents
 * @param offset Starting offset used for this result
 * @param lastScoreDoc Opaque cursor for searchAfter pagination;
 *  null when there are no more pages or when using offset-based pagination.
 *  Callers should treat this as an opaque token for cursor-based paging.
 * @since 1.20.13
 */
public record SearchResult(
    List<ArtifactDocument> documents,
    long totalHits,
    int offset,
    Object lastScoreDoc
) {

    /**
     * Backward-compatible constructor without cursor support.
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
        this(documents, totalHits, offset, null);
    }

    /**
     * Empty search result.
     */
    public static final SearchResult EMPTY = new SearchResult(List.of(), 0, 0, null);
}
