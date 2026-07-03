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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Maven (and Gradle) repositories.
 *
 * <p>Walks the Maven directory structure and treats any file whose name
 * matches {@code artifactId-version} (or {@code artifactId_version}) under
 * {@code groupId-as-path/artifactId/version/} as an artifact, regardless of
 * extension. Sidecars ({@code .sha1}, {@code .md5}, {@code .asc}, etc.),
 * {@code maven-metadata.xml}, and pantera-meta JSON files are filtered out
 * by a blocklist.</p>
 *
 * <p>This structural detection matches the same invariant used by the upload
 * path ({@code UploadSlice.isPrimaryArtifactPath}) and the group lookup
 * parser ({@code ArtifactNameParser.parseMaven}), so unusual extensions
 * (e.g. {@code .graphql}, {@code .tar.gz}) that are valid Maven artifacts
 * are indexed consistently across upload, scan, and lookup.</p>
 *
 * <p>When multiple files share the same GAV (e.g. {@code .jar} + {@code .pom}),
 * only one record is emitted; a binary beats a {@code .pom}/{@code .module},
 * and among binaries the largest file size wins.</p>
 *
 * @since 1.20.13
 */
final class MavenScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MavenScanner.class);

    /**
     * Repository type identifier.
     */
    private final String repoType;

    /**
     * Ctor.
     *
     * @param repoType Repository type ("maven" or "gradle")
     */
    MavenScanner(final String repoType) {
        this.repoType = repoType;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Map<String, ArtifactRecord> dedup = new HashMap<>();
        // Tracks which keys already have a non-POM (binary) artifact winner.
        final Set<String> hasBinary = new HashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(MavenScanner::isScannableCandidate)
                .forEach(path -> {
                    final ArtifactRecord record = this.parseFromPath(
                        root, repoName, path
                    );
                    if (record != null) {
                        final String key = String.format(
                            "%s:%s", record.name(), record.version()
                        );
                        final String fname = path.getFileName().toString();
                        final boolean incoming = !fname.endsWith(".pom")
                            && !fname.endsWith(".module");
                        if (!dedup.containsKey(key)) {
                            dedup.put(key, record);
                            if (incoming) {
                                hasBinary.add(key);
                            }
                        } else if (incoming && !hasBinary.contains(key)) {
                            // Replace POM-only entry with binary
                            dedup.put(key, record);
                            hasBinary.add(key);
                        } else if (incoming
                            && record.size() > dedup.get(key).size()) {
                            // Both binary — keep the larger one
                            dedup.put(key, record);
                        }
                        // POM incoming when binary already exists: ignored
                    }
                });
        }
        return dedup.values().stream();
    }

    /**
     * Cheap blocklist filter — rejects files that are obviously not
     * artifacts (hidden files, checksums, signatures, pantera sidecars,
     * {@code maven-metadata.xml}). The true structural invariant
     * ({@code filename.startsWith(artifactId + "-")}) is checked later
     * in {@link #parseFromPath} once the path has been decomposed.
     *
     * @param path File path to check
     * @return True if the file is worth inspecting as a potential artifact
     */
    private static boolean isScannableCandidate(final Path path) {
        final String name = path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        if (name.endsWith(".md5") || name.endsWith(".sha1")
            || name.endsWith(".sha256") || name.endsWith(".sha512")
            || name.endsWith(".asc") || name.endsWith(".sig")) {
            return false;
        }
        // pantera-meta JSON sidecars and Maven's own metadata XML.
        return !(name.endsWith(".pantera-meta.json")
            || "maven-metadata.xml".equals(name)
            || name.startsWith("maven-metadata.xml."));
    }

    /**
     * Parse an artifact record from the Maven directory structure.
     * Path convention: root/groupId-parts/artifactId/version/file.ext
     *
     * @param root Repository root
     * @param repoName Logical repository name
     * @param path Path to the artifact file
     * @return Artifact record, or null if path structure is invalid
     */
    private ArtifactRecord parseFromPath(final Path root,
        final String repoName, final Path path) {
        final Path relative = root.relativize(path);
        final int count = relative.getNameCount();
        // Need at least: groupId-part / artifactId / version / file
        if (count < 4) {
            LOG.debug("Path too short for Maven layout: {}", relative);
            return null;
        }
        final String version = relative.getName(count - 2).toString();
        final String artifactId = relative.getName(count - 3).toString();
        // Structural invariant shared with UploadSlice.isPrimaryArtifactPath
        // and ArtifactNameParser.parseMaven: the file must belong to this
        // artifact's coordinates. Accept both `-` (Maven) and `_` (legacy
        // internal) separators.
        final String filename = path.getFileName().toString();
        if (!filename.startsWith(artifactId + '-')
            && !filename.startsWith(artifactId + '_')) {
            LOG.debug("Filename {} does not match artifactId {}",
                filename, artifactId);
            return null;
        }
        final StringBuilder groupBuilder = new StringBuilder();
        for (int idx = 0; idx < count - 3; idx++) {
            if (idx > 0) {
                groupBuilder.append('.');
            }
            groupBuilder.append(relative.getName(idx).toString());
        }
        final String groupId = groupBuilder.toString();
        if (groupId.isEmpty()) {
            return null;
        }
        final char sep = this.repoType.startsWith("gradle") ? ':' : '.';
        final String name = groupId + sep + artifactId;
        long size = 0L;
        long mtime;
        try {
            final BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class
            );
            size = attrs.size();
            mtime = attrs.lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            mtime = System.currentTimeMillis();
        }
        // Proxy repos need the directory path for artifact lookup;
        // local/hosted repos use NULL (no prefix stored in production).
        final String pathPrefix;
        if (this.repoType.endsWith("-proxy")) {
            final Path relParent = relative.getParent();
            pathPrefix = relParent != null
                ? relParent.toString().replace('\\', '/') : null;
        } else {
            pathPrefix = null;
        }
        final Long releaseDate =
            PanteraMetaSidecar.readReleaseDate(path).orElse(null);
        return new ArtifactRecord(
            this.repoType, repoName, name, version,
            size, mtime, releaseDate, "system", pathPrefix
        );
    }
}
