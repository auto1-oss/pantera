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

import com.auto1.pantera.index.SearchQueryParser.FieldFilter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Capability interface for indexes that can push both the caller's
 * repository scope AND structured field filters ({@code name:}, {@code
 * version:}) into the query, so documents, {@code totalHits} and the
 * type/repository facets are all computed over the authorised set.
 *
 * <p>SECURITY (2.2.9, search-authz): {@code SearchHandler} used to detect
 * the DB index with {@code instanceof DbArtifactIndex}. In production the
 * index is always wrapped in {@link ArtifactIndexCache}, so that branch was
 * dead and the handler fell through to the scope-less overload — the
 * aggregates were then served unscoped. Dispatching on this capability
 * (implemented by both the DB index and its cache wrapper) keeps the
 * scoped path live regardless of wrapping.</p>
 *
 * @since 2.2.9
 */
public interface ScopedSearchIndex {

    /**
     * Scoped, field-filtered search.
     * @param query Full-text query (may be blank for filter-only searches)
     * @param maxResults Maximum results
     * @param offset Pagination offset
     * @param repoType Optional repository type base filter
     * @param repoName Optional exact repository name filter
     * @param sortBy Sort field
     * @param sortAsc True for ascending
     * @param allowedRepos {@code null} = unrestricted, empty = deny all,
     *  else the readable repository allow-list
     * @param fieldFilters Structured field filters
     * @return Scoped result
     */
    CompletableFuture<SearchResult> searchScoped(
        String query, int maxResults, int offset,
        String repoType, String repoName, String sortBy, boolean sortAsc,
        List<String> allowedRepos, List<FieldFilter> fieldFilters
    );
}
