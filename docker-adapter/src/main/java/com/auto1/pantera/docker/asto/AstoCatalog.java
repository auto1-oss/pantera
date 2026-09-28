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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asto implementation of {@link Catalog}. Catalog created from list of keys.
 *
 * @since 0.9
 */
final class AstoCatalog implements Catalog {

    /**
     * Folder holding an image's manifests and tags.
     */
    private static final String MANIFESTS = "_manifests";

    /**
     * Repositories root key.
     */
    private final Key root;

    /**
     * List of keys inside repositories root.
     */
    private final Collection<Key> keys;
    private final Pagination pagination;

    /**
     * @param root Repositories root key.
     * @param keys List of keys inside repositories root.
     * @param pagination Pagination parameters.
     */
    AstoCatalog(Key root, Collection<Key> keys, Pagination pagination) {
        this.root = root;
        this.keys = keys;
        this.pagination = pagination;
    }

    @Override
    public Content json() {
        return new CatalogPage(this.repos(), this.pagination).json();
    }

    /**
     * Convert keys to ordered set of repository names.
     *
     * <p>An image name may span several path segments
     * ({@code team/nested/img}), so the name is every segment between the
     * repositories root and the image's {@code _manifests} folder. Taking
     * only the first segment below the root listed names such as
     * {@code team} that no client can pull. A name part never starts with
     * {@code _} (see {@code ImageRepositoryName}), so the first such segment
     * ends the name; a name holding only an unfinished upload or layer links
     * has no manifests and is not an image.</p>
     *
     * @return Ordered repository names.
     */
    private Collection<String> repos() {
        final List<String> base = this.root.parts();
        final Set<String> names = new TreeSet<>();
        for (final Key key : this.keys) {
            AstoCatalog.imageName(base, key.parts()).ifPresent(names::add);
        }
        return names;
    }

    /**
     * Image name a storage key belongs to.
     *
     * @param base Repositories root parts
     * @param parts Key parts
     * @return Image name when the key lies in an image's manifests folder
     */
    private static Optional<String> imageName(final List<String> base, final List<String> parts) {
        Optional<String> name = Optional.empty();
        if (parts.size() > base.size() && parts.subList(0, base.size()).equals(base)) {
            int idx = base.size();
            while (idx < parts.size() && !parts.get(idx).startsWith("_")) {
                idx += 1;
            }
            if (idx > base.size() && idx < parts.size()
                && AstoCatalog.MANIFESTS.equals(parts.get(idx))) {
                name = Optional.of(String.join("/", parts.subList(base.size(), idx)));
            }
        }
        return name;
    }
}
