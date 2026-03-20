/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
