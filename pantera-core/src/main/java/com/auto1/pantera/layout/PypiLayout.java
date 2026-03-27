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
 * Python (PyPI) repository layout.
 * Structure: {@code <repo-name>/<artifact_name>/<version>/artifacts}
 *
 * @since 1.0
 */
public final class PypiLayout implements StorageLayout {

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        return new Key.From(
            artifact.repository(),
            artifact.name(),
            artifact.version()
        );
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        // PyPI metadata is typically stored alongside artifacts
        return new Key.From(
            artifact.repository(),
            artifact.name(),
            artifact.version()
        );
    }
}
