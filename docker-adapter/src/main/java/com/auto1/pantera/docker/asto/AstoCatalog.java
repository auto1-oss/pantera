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
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.misc.CatalogPage;
import com.auto1.pantera.docker.misc.Pagination;

import java.util.Collection;
import java.util.Optional;

/**
 * Asto implementation of {@link Catalog}. Catalog created from list of keys.
 *
 * @since 0.9
 */
final class AstoCatalog implements Catalog {

    /**
     * Paginated catalog page, computed eagerly from the given keys.
     */
    private final CatalogPage page;

    /**
     * @param root Repositories root key.
     * @param keys List of keys inside repositories root.
     * @param pagination Pagination parameters.
     */
    AstoCatalog(Key root, Collection<Key> keys, Pagination pagination) {
        this.page = new CatalogPage(new Children(root, keys).names(), pagination);
    }

    @Override
    public Content json() {
        return this.page.json();
    }

    @Override
    public boolean hasNext() {
        return this.page.hasNext();
    }

    @Override
    public Optional<String> nextCursor() {
        return this.page.nextCursor();
    }
}
