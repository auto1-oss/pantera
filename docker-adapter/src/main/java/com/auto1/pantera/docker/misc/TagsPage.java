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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Tags;

import javax.json.Json;
import java.util.List;

/**
 * {@link Tags} that is a page of given tags list.
 */
public final class TagsPage implements Tags {

    private final String repoName;

    private final List<String> tags;

    private final Pagination pagination;

    /**
     * Whether every source of the tags answered.
     */
    private final boolean whole;

    /**
     * Whether a source holds the repository name.
     */
    private final boolean held;

    /**
     * @param repoName Repository name.
     * @param tags Tags.
     * @param pagination Pagination parameters.
     */
    public TagsPage(String repoName, List<String> tags, Pagination pagination) {
        this(repoName, tags, pagination, true, true);
    }

    /**
     * @param repoName Repository name.
     * @param tags Tags.
     * @param pagination Pagination parameters.
     * @param complete Whether every source of the tags answered.
     */
    public TagsPage(
        String repoName, List<String> tags, Pagination pagination, boolean complete
    ) {
        this(repoName, tags, pagination, complete, true);
    }

    /**
     * @param repoName Repository name.
     * @param tags Tags.
     * @param pagination Pagination parameters.
     * @param complete Whether every source of the tags answered.
     * @param known Whether a source holds the repository name.
     */
    public TagsPage(
        String repoName, List<String> tags, Pagination pagination, boolean complete,
        boolean known
    ) {
        this.repoName = repoName;
        this.tags = tags;
        this.pagination = pagination;
        this.whole = complete;
        this.held = known;
    }

    @Override
    public boolean known() {
        return this.held;
    }

    @Override
    public boolean complete() {
        return this.whole;
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", this.repoName)
                .add("tags", pagination.apply(tags.stream()))
                .build()
                .toString()
                .getBytes()
        );
    }
}
