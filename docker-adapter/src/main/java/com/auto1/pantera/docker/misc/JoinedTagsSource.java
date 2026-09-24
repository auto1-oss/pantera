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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Source of tags built by loading and merging multiple tag lists.
 */
public final class JoinedTagsSource {

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Manifests for reading.
     */
    private final List<Manifests> manifests;

    /**
     * @param repo Repository name.
     * @param pagination Pagination parameters.
     * @param manifests Sources to load tags from.
     */
    public JoinedTagsSource(String repo, Pagination pagination, Manifests... manifests) {
        this(repo, Arrays.asList(manifests), pagination);
    }

    private final Pagination pagination;

    /**
     * @param repo Repository name.
     * @param manifests Sources to load tags from.
     * @param pagination Pagination pagination.
     */
    public JoinedTagsSource(String repo, List<Manifests> manifests, Pagination pagination) {
        this.repo = repo;
        this.manifests = manifests;
        this.pagination = pagination;
    }

    /**
     * Load tags.
     *
     * @return Tags.
     */
    public CompletableFuture<Tags> tags() {
        // A source that fails still contributes no tags (the others may
        // know the name), but the joined listing is then marked
        // incomplete so an empty result is not taken as NAME_UNKNOWN proof.
        final AtomicBoolean complete = new AtomicBoolean(true);
        // The name is known when a source that answered holds it; a failed
        // source proves nothing either way.
        final AtomicBoolean known = new AtomicBoolean(false);
        CompletableFuture<List<String>>[] futs = new CompletableFuture[manifests.size()];
        for (int i = 0; i < manifests.size(); i++) {
            futs[i] = this.load(manifests.get(i))
                .thenCompose(tags -> {
                    if (!tags.complete()) {
                        complete.set(false);
                    }
                    if (tags.known()) {
                        known.set(true);
                    }
                    return new ParsedTags(tags).tags();
                })
                .toCompletableFuture()
                .exceptionally(err -> {
                    complete.set(false);
                    return Collections.emptyList();
                });
        }
        return CompletableFuture.allOf(futs)
            .thenApply(v -> {
                final List<String> names = new ArrayList<>();
                Arrays.stream(futs).forEach(fut -> names.addAll(fut.getNow(List.of())));
                return new TagsPage(repo, names, pagination, complete.get(), known.get());
            });
    }

    /**
     * Load tags from one source, turning a synchronous throw into a failed
     * future.
     *
     * @param source Manifests
     * @return Tags future
     */
    private CompletableFuture<Tags> load(final Manifests source) {
        try {
            return source.tags(this.pagination);
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
