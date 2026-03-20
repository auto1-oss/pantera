/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import com.artipie.asto.Key;

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
