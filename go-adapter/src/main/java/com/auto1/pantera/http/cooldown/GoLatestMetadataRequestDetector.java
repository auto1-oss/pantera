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
 * Go {@code /@latest} metadata request detector implementing cooldown SPI.
 *
 * <p>Detects Go proxy {@code /{module}/@latest} requests. This is the
 * companion to {@link GoMetadataRequestDetector} (which handles
 * {@code /@v/list}): when {@code go get} runs without an explicit
 * pseudo-version, the Go client hits {@code /@latest} first and never
 * touches {@code /@v/list} — so a {@code @v/list}-only filter leaves
 * the primary unbounded-resolution workflow unprotected.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code /github.com/foo/bar/@latest} &rarr; matches, module = {@code github.com/foo/bar}</li>
 *   <li>{@code /@latest} &rarr; no module, no match</li>
 *   <li>{@code /foo/@latest/extra} &rarr; suffix not at end, no match</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class GoLatestMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Suffix that identifies a Go {@code @latest} metadata endpoint.
     */
    private static final String LATEST_SUFFIX = "/@latest";

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "go";

    @Override
    public boolean isMetadataRequest(final String path) {
        return path != null && path.endsWith(LATEST_SUFFIX);
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (!this.isMetadataRequest(path)) {
            return Optional.empty();
        }
        // path = "/{module}/@latest"  ->  module = path between leading "/" and "/@latest"
        final int end = path.length() - LATEST_SUFFIX.length();
        if (end <= 1) {
            // Path is "/@latest" or shorter — no module name present
            return Optional.empty();
        }
        return Optional.of(path.substring(1, end));
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
