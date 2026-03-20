/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;

/**
 * Storage layout interface for organizing artifacts in different repository types.
 * This interface defines how artifacts should be organized in storage across
 * all backend types (FS, S3, etc.).
 *
 * @since 1.0
 */
public interface StorageLayout {

    /**
     * Get the storage key (path) for an artifact.
     *
     * @param artifact Artifact information
     * @return Storage key where the artifact should be stored
     */
    Key artifactPath(ArtifactInfo artifact);

    /**
     * Get the storage key for metadata files.
     *
     * @param artifact Artifact information
     * @return Storage key for metadata
     */
    Key metadataPath(ArtifactInfo artifact);

    /**
     * Artifact information container.
     */
    interface ArtifactInfo {
        /**
         * Repository name.
         * @return Repository name
         */
        String repository();

        /**
         * Artifact name or identifier.
         * @return Artifact name
         */
        String name();

        /**
         * Artifact version (if applicable).
         * @return Version or empty
         */
        String version();

        /**
         * Additional metadata specific to repository type.
         * @param key Metadata key
         * @return Metadata value or null
         */
        String metadata(String key);
    }
}
