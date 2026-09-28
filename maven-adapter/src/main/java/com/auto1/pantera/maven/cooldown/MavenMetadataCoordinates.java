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
package com.auto1.pantera.maven.cooldown;

import java.util.List;
import java.util.Optional;

/**
 * Cooldown coordinates of a {@code maven-metadata.xml} request path — the
 * single place the Maven proxy and group derive the dotted package name the
 * cooldown layer keys block rows, filtered-metadata envelopes and
 * package-change events by.
 *
 * <ul>
 *   <li>{@code /com/example/my-lib/maven-metadata.xml} &rarr; package
 *       {@code com.example.my-lib}, artifact-level (no snapshot dir).</li>
 *   <li>{@code /com/example/my-lib/1.0-SNAPSHOT/maven-metadata.xml} &rarr;
 *       package {@code com.example.my-lib} (the version directory is NOT part
 *       of the package: snapshot downloads record blocks as artifact
 *       {@code com.example.my-lib} + timestamped version), snapshot dir
 *       {@code 1.0-SNAPSHOT}.</li>
 * </ul>
 *
 * <p>Checksum sidecars of metadata ({@code .sha1}, {@code .md5},
 * {@code .sha256}, {@code .sha512}) map to the same coordinates as the
 * metadata file they describe. That mapping exists for invalidation only:
 * a sidecar path must never be used to look up a filtered-metadata envelope
 * (the envelope holds the metadata XML, not a checksum) — proxies and groups
 * answer sidecars with the digest of the served metadata bytes.</p>
 *
 * <p>Only the path &rarr; package direction exists: dotted &rarr; slashed is
 * ambiguous (artifactIds may contain dots), so callers holding a package
 * name compare it against {@link #packageName(String)} of each path.</p>
 *
 * @since 2.2.9
 */
public final class MavenMetadataCoordinates {

    /**
     * Metadata filename.
     */
    private static final String METADATA = "maven-metadata.xml";

    /**
     * Snapshot version directory suffix.
     */
    private static final String SNAPSHOT = "-SNAPSHOT";

    /**
     * Checksum sidecar suffixes of a metadata file.
     */
    private static final List<String> SIDECARS = List.of(".sha1", ".md5", ".sha256", ".sha512");

    /**
     * Dotted cooldown package name of a metadata (or metadata-sidecar) path.
     *
     * @param path Request path, with or without leading slash
     * @return Dotted package, or empty when the path is not metadata
     */
    public Optional<String> packageName(final String path) {
        return MavenMetadataCoordinates.parentDir(path).flatMap(parent -> {
            String pkg = parent;
            if (MavenMetadataCoordinates.snapshotDir(parent).isPresent()) {
                final int slash = parent.lastIndexOf('/');
                pkg = slash > 0 ? parent.substring(0, slash) : "";
            }
            return pkg.isEmpty() ? Optional.empty() : Optional.of(pkg.replace('/', '.'));
        });
    }

    /**
     * Snapshot version directory of a snapshot-level metadata path.
     *
     * @param path Request path
     * @return Directory such as {@code 1.0-SNAPSHOT}, or empty for an
     *  artifact-level (or non-metadata) path
     */
    public Optional<String> snapshotVersionDir(final String path) {
        return MavenMetadataCoordinates.parentDir(path)
            .flatMap(MavenMetadataCoordinates::snapshotDir);
    }

    /**
     * Filtered-metadata envelope variant for a snapshot-level metadata path:
     * {@code snapshot-<dir>}. The envelope cache key is
     * {@code metadata:{type}:{repo}:{variant}:{pkg}}, so the variant must not
     * contain {@code ':'}; any colon is replaced defensively.
     *
     * @param path Request path
     * @return Variant, or empty for artifact-level metadata (default variant)
     */
    public Optional<String> envelopeVariant(final String path) {
        return this.snapshotVersionDir(path)
            .map(dir -> "snapshot-" + dir.replace(':', '_'));
    }

    /**
     * Directory holding the metadata file, without leading slash.
     *
     * @param path Request path
     * @return Parent directory, or empty when not a metadata path
     */
    private static Optional<String> parentDir(final String path) {
        if (path == null) {
            return Optional.empty();
        }
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        for (final String ext : SIDECARS) {
            if (stripped.endsWith(METADATA + ext)) {
                stripped = stripped.substring(0, stripped.length() - ext.length());
                break;
            }
        }
        final String suffix = "/" + METADATA;
        if (!stripped.endsWith(suffix) || stripped.length() == suffix.length()) {
            return Optional.empty();
        }
        return Optional.of(stripped.substring(0, stripped.length() - suffix.length()));
    }

    /**
     * Last segment of {@code parent} when it is a SNAPSHOT version dir.
     *
     * @param parent Parent directory
     * @return The directory name, or empty
     */
    private static Optional<String> snapshotDir(final String parent) {
        final int slash = parent.lastIndexOf('/');
        final String dir = parent.substring(slash + 1);
        if (slash > 0 && dir.endsWith(SNAPSHOT) && dir.length() > SNAPSHOT.length()) {
            return Optional.of(dir);
        }
        return Optional.empty();
    }
}
