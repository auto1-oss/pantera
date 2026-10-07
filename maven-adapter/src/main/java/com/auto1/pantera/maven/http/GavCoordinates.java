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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Key;

import java.util.Arrays;
import java.util.Optional;

/**
 * Group/artifact/version coordinates parsed from a hosted upload path,
 * shared by {@link UploadSlice}'s release-immutability (WS4-maven.6),
 * checksum-mismatch (WS4-maven.5), PGP-verification-failure (WS4-maven.2)
 * audit paths, and the {@code maven-metadata.xml} regeneration trigger
 * (WS4-maven.4) — one parse, four consumers, no divergent re-derivations.
 *
 * @param baseKey {@code groupId-path/artifactId} — the GA-level directory
 *                {@code maven-metadata.xml} lives under
 * @param groupId Dotted Maven group id
 * @param artifactId Maven artifact id
 * @param version Version directory segment
 * @param artifactName {@code groupId.artifactId} — matches
 *                     {@link MavenSlice#EVENT_INFO}'s
 *                     {@code formatArtifactName} convention, used as
 *                     {@code package.name} in audit records
 * @since 2.3.0
 */
record GavCoordinates(Key baseKey, String groupId, String artifactId, String version, String artifactName) {

    /**
     * Parse a storage key path of the form
     * {@code groupId/segments/artifactId/version/filename} into GAV
     * coordinates. Requires at least one group-id segment; a path with
     * fewer than 4 segments (no room for group + artifact + version +
     * filename) does not parse.
     *
     * @param rawPath Key path, with or without a leading slash
     * @return Parsed coordinates, or empty when the path is too short to
     *         be a GAV coordinate
     */
    static Optional<GavCoordinates> parse(final String rawPath) {
        final String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        final String[] segments = path.split("/");
        if (segments.length < 4) {
            return Optional.empty();
        }
        final String artifactId = segments[segments.length - 3];
        final String version = segments[segments.length - 2];
        final String groupPath = String.join(
            "/", Arrays.asList(segments).subList(0, segments.length - 3)
        );
        if (groupPath.isEmpty()) {
            return Optional.empty();
        }
        final String groupId = groupPath.replace('/', '.');
        final Key baseKey = new Key.From(groupPath + "/" + artifactId);
        final String artifactName = MavenSlice.EVENT_INFO.formatArtifactName(
            groupPath + "/" + artifactId
        );
        return Optional.of(new GavCoordinates(baseKey, groupId, artifactId, version, artifactName));
    }
}
