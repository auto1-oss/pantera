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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;

/**
 * Go metadata request detector implementing cooldown SPI.
 * Detects Go version-list metadata requests ({@code /{module}/@v/list}).
 *
 * @since 2.2.0
 */
public final class GoMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Suffix that identifies a Go version-list metadata endpoint.
     */
    private static final String LIST_SUFFIX = "/@v/list";

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "go";

    @Override
    public boolean isMetadataRequest(final String path) {
        return path != null && path.endsWith(LIST_SUFFIX);
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (!this.isMetadataRequest(path)) {
            return Optional.empty();
        }
        // path = "/{module}/@v/list"  ->  module = path between leading "/" and "/@v/list"
        final int end = path.length() - LIST_SUFFIX.length();
        if (end <= 1) {
            // Path is "/@v/list" or shorter — no module name present
            return Optional.empty();
        }
        return Optional.of(path.substring(1, end));
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
