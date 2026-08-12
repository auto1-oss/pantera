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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Hex (Erlang/Elixir) repositories.
 *
 * <p>Hex storage is flat by design: {@code packages/<name>} is a single
 * JSON metadata file describing every release, and the actual release
 * tarballs live under {@code tarballs/<name>-<version>.tar} — there is
 * never a per-package directory. This scanner walks {@code tarballs/} and
 * parses each filename to recover the package name and version, and
 * records the tarball's repo-relative path as {@code pathPrefix} so
 * browse-to-directory can resolve the real flat layout instead of guessing
 * a per-package directory that never exists.</p>
 *
 * @since 1.20.13
 */
final class HexScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(HexScanner.class);

    /**
     * Pattern for tarball filenames: {@code {name}-{version}.tar}. Hex
     * package names are conventionally hyphen-free (lowercase letters,
     * digits, underscores), but the version is still identified as the
     * first hyphen-separated segment that starts with a digit, mirroring
     * {@link GemScanner}, so an unexpected hyphenated name degrades
     * gracefully instead of mis-parsing.
     */
    private static final Pattern TARBALL_PATTERN = Pattern.compile(
        "^(?<name>.+?)-(?<version>\\d[A-Za-z0-9._+-]*)[.]tar$"
    );

    /**
     * Name of the tarballs subdirectory.
     */
    private static final String TARBALLS_DIR = "tarballs";

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Path base = root.resolve(HexScanner.TARBALLS_DIR);
        if (!Files.isDirectory(base)) {
            return Stream.empty();
        }
        return Files.walk(base, 1)
            .filter(Files::isRegularFile)
            .filter(path -> !path.getFileName().toString().startsWith("."))
            .filter(path -> path.getFileName().toString().endsWith(".tar"))
            .flatMap(path -> this.tryParse(repoName, path, root));
    }

    /**
     * Attempt to parse a tarball file path into an artifact record.
     *
     * @param repoName Logical repository name
     * @param path Tarball file path to parse
     * @param root Repository root, used to compute the repo-relative real
     *     storage key stored as {@code pathPrefix}
     * @return Stream with a single record, or empty if filename does not match
     */
    private Stream<ArtifactRecord> tryParse(final String repoName,
        final Path path, final Path root) {
        final String filename = path.getFileName().toString();
        final Matcher matcher = HexScanner.TARBALL_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            LOG.debug(
                "Skipping non-conforming hex tarball filename: {}", filename
            );
            return Stream.empty();
        }
        final String name = matcher.group("name");
        final String version = matcher.group("version");
        try {
            final BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class
            );
            final String pathPrefix = root.relativize(path).toString()
                .replace('\\', '/');
            return Stream.of(
                new ArtifactRecord(
                    "hexpm",
                    repoName,
                    name,
                    version,
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    null,
                    "system",
                    pathPrefix
                )
            );
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
