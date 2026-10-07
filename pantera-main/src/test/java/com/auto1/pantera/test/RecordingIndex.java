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
package com.auto1.pantera.test;

import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory search index holding {@code repo|path} rows and recording every
 * {@link #removeByPath} call, for delete-cascade tests.
 *
 * @since 2.2.10
 */
public final class RecordingIndex implements ArtifactIndex {

    /**
     * Rows as {@code repo|path}.
     */
    private final Set<String> rows = ConcurrentHashMap.newKeySet();

    /**
     * {@code removeByPath} calls as {@code repo|path}.
     */
    private final List<String> removals = new CopyOnWriteArrayList<>();

    /**
     * Add a row.
     * @param repo Repository
     * @param path Path
     * @return Itself
     */
    public RecordingIndex row(final String repo, final String path) {
        this.rows.add(repo + "|" + path);
        return this;
    }

    /**
     * Current rows.
     * @return Rows as {@code repo|path}
     */
    public Set<String> rows() {
        return Set.copyOf(this.rows);
    }

    /**
     * Recorded {@code removeByPath} calls.
     * @return Calls as {@code repo|path}
     */
    public List<String> removals() {
        return List.copyOf(this.removals);
    }

    @Override
    public CompletableFuture<Void> index(final ArtifactDocument doc) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> remove(final String repo, final String path) {
        this.rows.remove(repo + "|" + path);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Integer> removeByPath(final String repo, final String path) {
        this.removals.add(repo + "|" + path);
        final String exact = repo + "|" + path;
        final String under = exact + "/";
        int removed = 0;
        for (final String row : List.copyOf(this.rows)) {
            if (row.equals(exact) || row.startsWith(under)) {
                this.rows.remove(row);
                removed += 1;
            }
        }
        return CompletableFuture.completedFuture(removed);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int max, final int offset
    ) {
        return CompletableFuture.completedFuture(SearchResult.EMPTY);
    }

    @Override
    public CompletableFuture<List<String>> locate(final String path) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public void close() {
        // nothing to close
    }
}
