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
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;

/**
 * Composer repository layout.
 * Structure: {@code <repo-name>/<artifact_name>/<version>/artifacts}
 * If artifact name contains /, then parent folder is before / and sub folder after.
 * For example, if in composer.json we have "name": "x/y",
 * then folder structure will be {@code <repo-name>/x/y/<version>/artifacts}
 *
 * @since 1.0
 */
public final class ComposerLayout implements StorageLayout {

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        final String name = artifact.name();
        
        // Split by / if present (vendor/package format)
        if (name.contains("/")) {
            final String[] parts = name.split("/", 2);
            return new Key.From(
                artifact.repository(),
                parts[0],
                parts[1],
                artifact.version()
            );
        }
        
        // Single name without vendor
        return new Key.From(
            artifact.repository(),
            name,
            artifact.version()
        );
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        final String name = artifact.name();
        
        // Split by / if present (vendor/package format)
        if (name.contains("/")) {
            final String[] parts = name.split("/", 2);
            return new Key.From(
                artifact.repository(),
                parts[0],
                parts[1]
            );
        }
        
        // Single name without vendor
        return new Key.From(
            artifact.repository(),
            name
        );
    }
}
