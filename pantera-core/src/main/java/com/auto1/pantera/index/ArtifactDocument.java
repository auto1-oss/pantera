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
package com.auto1.pantera.index;

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
 * @param pathPrefix Real storage key backing this artifact (nullable — only
 *     populated for writers that record it; see {@code ArtifactEvent#pathPrefix()}).
 *     Unlike {@code artifactPath}, this is always an actual storage key when
 *     present, never a synthetic display name.
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
    String owner,
    String pathPrefix
) {

    /**
     * Ctor.
     */
    public ArtifactDocument {
        Objects.requireNonNull(repoType, "repoType");
        Objects.requireNonNull(repoName, "repoName");
        Objects.requireNonNull(artifactPath, "artifactPath");
    }

    /**
     * Back-compat ctor for callers that predate {@code pathPrefix} — defaults
     * it to {@code null} (no known real storage key).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifactPath Full artifact path
     * @param artifactName Human-readable artifact name
     * @param version Artifact version
     * @param size Artifact size in bytes
     * @param createdAt Creation timestamp
     * @param owner Owner/uploader username (nullable)
     */
    public ArtifactDocument(
        final String repoType, final String repoName, final String artifactPath,
        final String artifactName, final String version, final long size,
        final Instant createdAt, final String owner
    ) {
        this(repoType, repoName, artifactPath, artifactName, version, size, createdAt, owner, null);
    }
}
