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
import com.auto1.pantera.docker.Catalog;

import javax.json.Json;
import java.util.Collection;
import java.util.Optional;

/**
 * {@link Catalog} that is a page of given repository names list.
 *
 * @since 0.10
 */
public final class CatalogPage implements Catalog {

    private final Pagination.Page page;

    /**
     * @param names Repository names.
     * @param pagination Pagination parameters.
     */
    public CatalogPage(Collection<String> names, Pagination pagination) {
        this.page = pagination.page(names.stream());
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("repositories", this.page.json())
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
