/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
