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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.misc.Pagination;

import javax.json.Json;
import java.util.Collection;
import java.util.Optional;

/**
 * Asto implementation of {@link Tags}. Tags created from list of keys.
 *
 * @since 0.8
 */
final class AstoTags implements Tags {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Paginated tags page, computed eagerly from the given keys.
     */
    private final Pagination.Page page;

    /**
     * @param name Image repository name.
     * @param root Tags root key.
     * @param keys List of keys inside tags root.
     * @param pagination Pagination parameters.
     */
    AstoTags(String name, Key root, Collection<Key> keys, Pagination pagination) {
        this.name = name;
        this.page = pagination.page(new Children(root, keys).names().stream());
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", this.name)
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
