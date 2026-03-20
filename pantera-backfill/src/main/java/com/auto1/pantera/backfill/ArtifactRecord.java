/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
