/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;

/**
 * Maven repository layout.
 * Structure: {@code <repo-name>/<groupId>/<artifactId>/<version>/artifacts}
 * where groupId x.y.z becomes folder structure x/y/z
 * maven-metadata.xml is stored under {@code <artifactId>}
 *
 * @since 1.0
 */
public final class MavenLayout implements StorageLayout {

    /**
     * Metadata key for groupId.
     */
    public static final String GROUP_ID = "groupId";

    /**
     * Metadata key for artifactId.
     */
    public static final String ARTIFACT_ID = "artifactId";

    /**
     * Metadata filename.
     */
    private static final String METADATA_FILE = "maven-metadata.xml";

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        final String groupId = artifact.metadata(GROUP_ID);
        final String artifactId = artifact.metadata(ARTIFACT_ID);
        
        if (groupId == null || artifactId == null) {
            throw new IllegalArgumentException(
                "Maven layout requires 'groupId' and 'artifactId' metadata"
            );
        }

        // Convert groupId dots to slashes (e.g., com.example -> com/example)
        final String groupPath = groupId.replace('.', '/');
        
        return new Key.From(
            artifact.repository(),
            groupPath,
            artifactId,
            artifact.version()
        );
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        final String groupId = artifact.metadata(GROUP_ID);
        final String artifactId = artifact.metadata(ARTIFACT_ID);
        
        if (groupId == null || artifactId == null) {
            throw new IllegalArgumentException(
                "Maven layout requires 'groupId' and 'artifactId' metadata"
            );
        }

        final String groupPath = groupId.replace('.', '/');
        
        return new Key.From(
            artifact.repository(),
            groupPath,
            artifactId,
            METADATA_FILE
        );
    }
}
