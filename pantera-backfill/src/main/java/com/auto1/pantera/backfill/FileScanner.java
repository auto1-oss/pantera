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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Scanner for generic file repositories.
 *
 * <p>Walks the directory tree rooted at the given path, filters out
 * hidden files (names starting with {@code .}), and maps every
 * regular file to an {@link ArtifactRecord} with {@code repoType="file"},
 * an empty version string, the file size, and the last-modified time
 * as the creation date.</p>
 *
 * @since 1.20.13
 */
final class FileScanner implements Scanner {

    /**
     * Repository type string stored in every produced artifact record
     * (e.g. {@code "file"} or {@code "file-proxy"}).
     */
    private final String repoType;

    /**
     * Owner string to set on every produced record.
     */
    private final String owner;

    /**
     * Ctor with default repo type {@code "file"} and owner {@code "system"}.
     */
    FileScanner() {
        this("file", "system");
    }

    /**
     * Ctor with given repo type and default owner {@code "system"}.
     *
     * @param repoType Repository type string for artifact records
     */
    FileScanner(final String repoType) {
        this(repoType, "system");
    }

    /**
     * Ctor.
     *
     * @param repoType Repository type string for artifact records
     * @param owner Owner identifier for produced records
     */
    FileScanner(final String repoType, final String owner) {
        this.repoType = repoType;
        this.owner = owner;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        return Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(path -> !path.getFileName().toString().startsWith("."))
            .map(path -> this.toRecord(root, repoName, path));
    }

    /**
     * Convert a file path to an artifact record.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param path File path
     * @return Artifact record
     */
    private ArtifactRecord toRecord(final Path root, final String repoName,
        final Path path) {
        try {
            final Path rel = root.relativize(path);
            final String name = rel.toString().replace('\\', '/').replace('/', '.');
            final String version = detectVersion(rel);
            return new ArtifactRecord(
                this.repoType,
                repoName,
                name,
                version,
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis(),
                null,
                this.owner,
                null
            );
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Infer a version from the file's parent directory.
     * If the immediate parent segment starts with a digit it is treated as a
     * version string (e.g. {@code 1.5.0-SNAPSHOT}, {@code 3.2.1}, {@code 2024}).
     * This mirrors the heuristic used by {@code ArtifactNameParser.parseMaven}.
     *
     * @param relative Path relative to the repository root
     * @return Version string, or {@code "UNKNOWN"} if not detectable
     */
    private static String detectVersion(final Path relative) {
        final int count = relative.getNameCount();
        if (count >= 2) {
            final String parent = relative.getName(count - 2).toString();
            if (!parent.isEmpty() && Character.isDigit(parent.charAt(0))) {
                return parent;
            }
        }
        return "UNKNOWN";
    }
}
