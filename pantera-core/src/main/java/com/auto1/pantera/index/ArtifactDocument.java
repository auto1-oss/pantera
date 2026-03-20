/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.index;

import java.time.Instant;
import java.util.Objects;

/**
 * Artifact document for the search index.
 *
 * @param repoType Repository type (e.g., "maven", "npm", "pypi")
 * @param repoName Repository name
 * @param artifactPath Full artifact path (unique per repo)
 * @param artifactName Human-readable artifact name (tokenized for search)
 * @param version Artifact version
 * @param size Artifact size in bytes
 * @param createdAt Creation timestamp
 * @param owner Owner/uploader username (nullable)
 * @since 1.20.13
 */
public record ArtifactDocument(
    String repoType,
    String repoName,
    String artifactPath,
    String artifactName,
    String version,
    long size,
    Instant createdAt,
    String owner
) {

    /**
     * Ctor.
     */
    public ArtifactDocument {
        Objects.requireNonNull(repoType, "repoType");
        Objects.requireNonNull(repoName, "repoName");
        Objects.requireNonNull(artifactPath, "artifactPath");
    }
}
