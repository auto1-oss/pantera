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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for PyPI repositories.
 *
 * <p>Walks the repository directory tree up to depth 2
 * (package-dir/filename), filters for recognized Python distribution
 * file extensions ({@code .whl}, {@code .tar.gz}, {@code .zip},
 * {@code .egg}), and regex-parses each filename to extract the
 * package name and version. Package names are normalized per PEP 503
 * (lowercase, consecutive {@code [-_.]} replaced with a single
 * {@code -}).</p>
 *
 * @since 1.20.13
 */
final class PypiScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(PypiScanner.class);

    /**
     * Repository type string stored in every produced artifact record
     * (e.g. {@code "pypi"} or {@code "pypi-proxy"}).
     */
    private final String repoType;

    /**
     * Ctor with default repo type {@code "pypi"}.
     */
    PypiScanner() {
        this("pypi");
    }

    /**
     * Ctor.
     *
     * @param repoType Repository type string for artifact records
     */
    PypiScanner(final String repoType) {
        this.repoType = repoType;
    }

    /**
     * Pattern for wheel filenames.
     * Example: my_package-1.0.0-py3-none-any.whl
     */
    private static final Pattern WHEEL = Pattern.compile(
        "(?<name>[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?)-(?<version>[0-9][A-Za-z0-9.!+_-]*?)(-\\d+)?-[A-Za-z0-9._]+-[A-Za-z0-9._]+-[A-Za-z0-9._]+\\.whl"
    );

    /**
     * Pattern for sdist filenames (tar.gz, zip, egg).
     * Example: requests-2.28.0.tar.gz
     */
    private static final Pattern SDIST = Pattern.compile(
        "(?<name>[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?)-(?<version>[0-9][A-Za-z0-9.!+_-]*)\\.(tar\\.gz|zip|egg)"
    );

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        return Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(path -> {
                for (Path part : root.relativize(path)) {
                    if (part.toString().startsWith(".")) {
                        return false;
                    }
                }
                return true;
            })
            .filter(PypiScanner::hasRecognizedExtension)
            .flatMap(path -> this.tryParse(root, repoName, path));
    }

    /**
     * Attempt to parse a file path into an artifact record.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param path File path to parse
     * @return Stream with a single record, or empty if filename does not match
     */
    private Stream<ArtifactRecord> tryParse(final Path root,
        final String repoName, final Path path) {
        final String filename = path.getFileName().toString();
        Matcher matcher = WHEEL.matcher(filename);
        if (!matcher.matches()) {
            matcher = SDIST.matcher(filename);
        }
        if (!matcher.matches()) {
            LOG.debug(
                "Skipping non-conforming filename: {}", filename
            );
            return Stream.empty();
        }
        final String name = normalizePep503(matcher.group("name"));
        final String version = matcher.group("version");
        try {
            final BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class
            );
            // Proxy repos need the full relative path for artifact lookup;
            // local/hosted repos use NULL (no prefix stored in production).
            final String pathPrefix = this.repoType.endsWith("-proxy")
                ? root.relativize(path).toString().replace('\\', '/') : null;
            final Long releaseDate =
                PanteraMetaSidecar.readReleaseDate(path).orElse(null);
            return Stream.of(
                new ArtifactRecord(
                    this.repoType,
                    repoName,
                    name,
                    version,
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    releaseDate,
                    "system",
                    pathPrefix
                )
            );
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Check whether a file path has a recognized Python distribution extension.
     *
     * @param path File path to check
     * @return True if the file has a recognized extension
     */
    private static boolean hasRecognizedExtension(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".whl")
            || name.endsWith(".tar.gz")
            || name.endsWith(".zip")
            || name.endsWith(".egg");
    }

    /**
     * Normalize a package name per PEP 503: lowercase and replace
     * consecutive runs of {@code [-_.]} with a single hyphen.
     *
     * @param name Raw package name
     * @return Normalized name
     */
    private static String normalizePep503(final String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
