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
package com.auto1.pantera.backfill;

/**
 * Represents a single artifact record to be inserted into the PostgreSQL
 * {@code artifacts} table.
 *
 * @param repoType Repository type identifier ("maven", "docker", "npm", etc.)
 * @param repoName Repository name from the CLI {@code --repo-name} argument
 * @param name Artifact coordinate (e.g. "com.example:mylib")
 * @param version Version string
 * @param size Artifact size in bytes
 * @param createdDate Creation timestamp as epoch millis (file mtime)
 * @param releaseDate Release timestamp as epoch millis, may be {@code null}
 * @param owner Owner identifier, defaults to "system"
 * @param pathPrefix Path prefix for group-repo lookup, may be {@code null}
 * @since 1.20.13
 */
public record ArtifactRecord(
    String repoType,
    String repoName,
    String name,
    String version,
    long size,
    long createdDate,
    Long releaseDate,
    String owner,
    String pathPrefix
) {
}
