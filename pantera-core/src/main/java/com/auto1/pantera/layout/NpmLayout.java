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
 * NPM repository layout.
 * Structure:
 * - Unscoped artifacts: {@code <repo-name>/<artifact_name>/-/artifacts}
 * - Scoped artifacts (@scope/name): {@code <repo-name>/@<scope_name>/<artifact_name>/-/artifacts}
 *
 * @since 1.0
 */
public final class NpmLayout implements StorageLayout {

    /**
     * Metadata key for scope.
     */
    public static final String SCOPE = "scope";

    /**
     * Separator for artifact directory.
     */
    private static final String ARTIFACT_DIR = "-";

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        final String scope = artifact.metadata(SCOPE);
        
        if (scope != null && !scope.isEmpty()) {
            // Scoped package: <repo-name>/@<scope>/<artifact_name>/-/
            final String scopeName = scope.startsWith("@") ? scope : "@" + scope;
            return new Key.From(
                artifact.repository(),
                scopeName,
                artifact.name(),
                ARTIFACT_DIR
            );
        } else {
            // Unscoped package: <repo-name>/<artifact_name>/-/
            return new Key.From(
                artifact.repository(),
                artifact.name(),
                ARTIFACT_DIR
            );
        }
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        final String scope = artifact.metadata(SCOPE);
        
        if (scope != null && !scope.isEmpty()) {
            // Scoped package metadata
            final String scopeName = scope.startsWith("@") ? scope : "@" + scope;
            return new Key.From(
                artifact.repository(),
                scopeName,
                artifact.name()
            );
        } else {
            // Unscoped package metadata
            return new Key.From(
                artifact.repository(),
                artifact.name()
            );
        }
    }
}
