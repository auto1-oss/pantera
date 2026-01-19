/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import com.artipie.asto.Key;

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
