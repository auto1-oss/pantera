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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.CatalogPage;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.misc.ParsedCatalog;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of {@link Docker} to remove given prefix from repository names.
 */
public final class TrimmedDocker implements Docker {

    /**
     * Docker origin.
     */
    private final Docker origin;

    /**
     * Regex to cut prefix from repository name.
     */
    private final String prefix;

    /**
     * Pre-compiled pattern used by {@link #trim(String)} to match and strip
     * the configured prefix from a repository name. Hoisted out of the hot
     * path to avoid re-compiling on every call.
     */
    private final Pattern trimPattern;

    /**
     * @param origin Docker origin
     * @param prefix Prefix to cut
     */
    public TrimmedDocker(Docker origin, String prefix) {
        this.origin = origin;
        this.prefix = prefix;
        this.trimPattern = Pattern.compile(
            String.format("(?:%s)\\/(.+)", prefix)
        );
    }

    @Override
    public String registryName() {
        return origin.registryName();
    }

    @Override
    public Repo repo(String name) {
        return this.origin.repo(trim(name));
    }

    @Override
    public CompletableFuture<Catalog> catalog(Pagination pagination) {
        Pagination trimmed = new Pagination(
            trim(pagination.last()), pagination.limit()
        );
        return this.origin.catalog(trimmed)
            .thenCompose(catalog -> new ParsedCatalog(catalog).repos())
            .thenApply(names -> names.stream()
                .map(name -> String.format("%s/%s", this.prefix, name))
                .toList())
            .thenApply(names -> new CatalogPage(names, pagination));
    }

    /**
     * Trim prefix from start of original name.
     *
     * @param name Original name.
     * @return Name reminder.
     */
    private String trim(String name) {
        if (name != null) {
            final Matcher matcher = this.trimPattern.matcher(name);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                    String.format(
                        "Invalid image name: name `%s` must start with `%s/`",
                        name, this.prefix
                    )
                );
            }
            return ImageRepositoryName.validate(matcher.group(1));
        }
        return null;
    }
}
