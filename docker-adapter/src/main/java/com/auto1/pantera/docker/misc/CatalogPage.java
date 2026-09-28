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

/**
 * {@link Catalog} that is a page of given repository names list.
 *
 * @since 0.10
 */
public final class CatalogPage implements Catalog {

    /**
     * Repository names.
     */
    private final Collection<String> names;

    private final Pagination pagination;

    /**
     * @param names Repository names.
     * @param pagination Pagination parameters.
     */
    public CatalogPage(Collection<String> names, Pagination pagination) {
        this.names = names;
        this.pagination = pagination;
    }

    @Override
    public Content json() {
        return new Content.From(
            Json.createObjectBuilder()
                .add("repositories", pagination.apply(names.stream()))
                .build()
                .toString()
                .getBytes()
        );
    }
}
