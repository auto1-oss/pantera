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
import java.util.Optional;

/**
 * {@link Tags} that is a page of given tags list.
 */
public final class TagsPage implements Tags {

    private final String repoName;

    private final Pagination.Page page;

    /**
     * @param repoName Repository name.
     * @param tags Tags.
     * @param pagination Pagination parameters.
     */
    public TagsPage(String repoName, List<String> tags, Pagination pagination) {
        this.repoName = repoName;
        this.page = pagination.page(tags.stream());
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", this.repoName)
                .add("tags", this.page.json())
                .build()
                .toString()
                .getBytes()
        );
    }

    @Override
    public boolean hasNext() {
        return this.page.truncated();
    }

    @Override
    public Optional<String> nextCursor() {
        return this.page.cursor();
    }
}
