/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;

/**
 * File repository layout.
 * Structure: Folders must be created as path of upload.
 * For example, if we get /file_repo/test/v3.2/file.gz
 * the folder structure must be {@code <repo_name>/test/v3.2/artifacts}
 *
 * @since 1.0
 */
public final class FileLayout implements StorageLayout {

    /**
     * Metadata key for upload path.
     */
    public static final String UPLOAD_PATH = "uploadPath";

    @Override
    public Key artifactPath(final ArtifactInfo artifact) {
        final String uploadPath = artifact.metadata(UPLOAD_PATH);
        
        if (uploadPath == null || uploadPath.isEmpty()) {
            throw new IllegalArgumentException(
                "File layout requires 'uploadPath' metadata"
            );
        }

        // Parse the upload path to extract directory structure
        // Remove leading slash if present
        String path = uploadPath.startsWith("/") ? uploadPath.substring(1) : uploadPath;
        
        // Remove repository name from path if it's included
        final String repoName = artifact.repository();
        if (path.startsWith(repoName + "/")) {
            path = path.substring(repoName.length() + 1);
        }
        
        // Extract directory path (everything except the filename)
        final int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            final String dirPath = path.substring(0, lastSlash);
            return new Key.From(repoName, dirPath);
        }
        
        // If no directory structure, store at repository root
        return new Key.From(repoName);
    }

    @Override
    public Key metadataPath(final ArtifactInfo artifact) {
        // File repositories typically don't have separate metadata
        return artifactPath(artifact);
    }
}
