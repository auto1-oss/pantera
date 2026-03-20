/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;

/**
 * Helm repository layout.
 * Structure: {@code <repo-name>/<chart_name>/artifacts}
 * index.yaml is stored under {@code <repo-name>}
 *
 * @since 1.0
 */
public final class HelmLayout implements StorageLayout {

    /**
     * Index filename.
     */
    private static final String INDEX_FILE = "index.yaml";

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        return new Key.From(
            artifact.repository(),
            artifact.name()
        );
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        // index.yaml is stored at the repository root
        return new Key.From(
            artifact.repository(),
            INDEX_FILE
        );
    }
}
