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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time metadata backfill for existing PyPI packages.
 *
 * <p>Walks a repository's storage directory looking for {@code .whl},
 * {@code .tar.gz}, {@code .zip}, and {@code .egg} distribution files.
 * For each file that does not already have a sidecar JSON at
 * {@code .pypi/metadata/{package}/{filename}.json} it:
 * <ol>
 *   <li>Extracts {@code Requires-Python} from the archive's PKG-INFO /
 *       METADATA entry.</li>
 *   <li>Derives {@code upload-time} from the file's last-modified
 *       timestamp.</li>
 *   <li>Writes the sidecar JSON file directly to the filesystem.</li>
 * </ol>
 *
 * <p>In {@code --dry-run} mode nothing is written; only what would happen
 * is logged.</p>
 *
 * @since 2.1.0
 */
final class PypiMetadataBackfill {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(PypiMetadataBackfill.class);

    /**
     * Relative path prefix for sidecar files inside a repo directory.
     */
    private static final String PYPI_META = ".pypi/metadata";

    /**
     * Root of all repositories storage.
     */
    private final Path storageRoot;

    /**
     * When true, log actions but do not write any files.
     */
    private final boolean dryRun;

    /**
     * Ctor.
     *
     * @param storageRoot Root of all repositories storage
     * @param dryRun When true, log actions but do not write any files
     */
    PypiMetadataBackfill(final Path storageRoot, final boolean dryRun) {
        this.storageRoot = storageRoot;
        this.dryRun = dryRun;
    }

    /**
     * Run the backfill for a single repository.
     *
     * @param repoName Repository name (sub-directory of storageRoot)
     * @return int array {@code [processed, created, skipped]}
     * @throws IOException On filesystem error
     */
    int[] backfill(final String repoName) throws IOException {
        final Path repoDir = this.storageRoot.resolve(repoName);
        if (!Files.isDirectory(repoDir)) {
            LOG.warn("Repository directory does not exist: {}", repoDir);
            return new int[]{0, 0, 0};
        }
        final int[] stats = {0, 0, 0};
        try (Stream<Path> walk = Files.walk(repoDir)) {
            walk.filter(Files::isRegularFile)
                .filter(PypiMetadataBackfill::isDistributionFile)
                .filter(path -> !isInsidePypiMeta(repoDir, path))
                .forEach(path -> {
                    stats[0]++;
                    final String filename = path.getFileName().toString();
                    final String packageName =
                        derivePackageName(repoDir, path);
                    final Path sidecar = repoDir
                        .resolve(PYPI_META)
                        .resolve(packageName)
                        .resolve(filename + ".json");
                    if (Files.exists(sidecar)) {
                        LOG.debug("Sidecar already exists, skipping: {}",
                            sidecar);
                        stats[2]++;
                        return;
                    }
                    final String requiresPython =
                        extractRequiresPython(path);
                    final Instant uploadTime =
                        lastModifiedInstant(path);
                    if (this.dryRun) {
                        LOG.info(
                            "[dry-run] Would create sidecar: {} "
                                + "(requires-python={}, upload-time={})",
                            sidecar, requiresPython, uploadTime
                        );
                        stats[1]++;
                        return;
                    }
                    try {
                        writeSidecar(
                            sidecar, requiresPython, uploadTime
                        );
                        LOG.debug(
                            "Created sidecar: {} "
                                + "(requires-python={}, upload-time={})",
                            sidecar, requiresPython, uploadTime
                        );
                        stats[1]++;
                    } catch (final IOException ex) {
                        LOG.warn(
                            "Failed to write sidecar {}: {}",
                            sidecar, ex.getMessage()
                        );
                    }
                });
        } catch (final UncheckedIOException ex) {
            throw ex.getCause();
        }
        return stats;
    }

    /**
     * Check whether the path is inside the {@code .pypi/metadata} subtree.
     *
     * @param repoDir Repository root directory
     * @param path Path to test
     * @return True if the path is inside the sidecar metadata directory
     */
    private static boolean isInsidePypiMeta(
        final Path repoDir, final Path path
    ) {
        return path.startsWith(repoDir.resolve(PYPI_META));
    }

    /**
     * Determine whether a file is a recognised Python distribution.
     *
     * @param path Path to test
     * @return True for {@code .whl}, {@code .tar.gz}, {@code .zip},
     *     {@code .egg}
     */
    private static boolean isDistributionFile(final Path path) {
        final String lower =
            path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".whl")
            || lower.endsWith(".tar.gz")
            || lower.endsWith(".zip")
            || lower.endsWith(".egg");
    }

    /**
     * Derive the PEP-503 normalized package name for a distribution file.
     *
     * <p>If the file sits directly under the repository root or is only
     * one level deep the filename itself is used as the package name (after
     * stripping the extension). For files inside a package sub-directory
     * the directory name is used.</p>
     *
     * @param repoDir Repository root directory
     * @param path Distribution file path
     * @return Normalized package name
     */
    private static String derivePackageName(
        final Path repoDir, final Path path
    ) {
        final Path relative = repoDir.relativize(path);
        if (relative.getNameCount() > 1) {
            // Use the first path segment as the package directory name
            return normalizePep503(relative.getName(0).toString());
        }
        // File is directly under the repo root — derive from filename
        final String filename = path.getFileName().toString();
        final String base;
        if (filename.toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
            base = filename.substring(0, filename.length() - ".tar.gz".length());
        } else {
            final int dot = filename.lastIndexOf('.');
            base = dot > 0 ? filename.substring(0, dot) : filename;
        }
        // Strip version suffix: everything after first '-' that is followed
        // by a digit is treated as the version
        final int dash = base.indexOf('-');
        final String nameOnly =
            dash > 0 ? base.substring(0, dash) : base;
        return normalizePep503(nameOnly);
    }

    /**
     * Normalize a package name per PEP 503.
     *
     * @param name Raw package name
     * @return Lowercase name with consecutive {@code [-_.]} collapsed to
     *     a single {@code -}
     */
    private static String normalizePep503(final String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /**
     * Extract the {@code Requires-Python} value from an archive.
     *
     * <p>Handles {@code .whl}, {@code .egg}, {@code .zip} (ZIP-based) and
     * {@code .tar.gz} archives. Failure to open or parse the archive
     * is logged as a warning and an empty string is returned.</p>
     *
     * @param path Path to the distribution archive
     * @return Requires-Python constraint string, or {@code ""} if absent
     *     or unreadable
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    static String extractRequiresPython(final Path path) {
        final String lower =
            path.getFileName().toString().toLowerCase(Locale.ROOT);
        try (InputStream raw = Files.newInputStream(path);
            InputStream buffered = new BufferedInputStream(raw)) {
            final Optional<String> result;
            if (lower.endsWith(".whl")
                || lower.endsWith(".egg")
                || lower.endsWith(".zip")) {
                result = readZipBased(buffered);
            } else if (lower.endsWith(".tar.gz")) {
                result = readTarGz(buffered);
            } else {
                return "";
            }
            return result.orElse("");
        } catch (final IOException ex) {
            LOG.warn(
                "Could not extract Requires-Python from {}: {}",
                path.getFileName(), ex.getMessage()
            );
            return "";
        }
    }

    /**
     * Read Requires-Python from a ZIP-based archive (wheel / egg / zip).
     *
     * @param input Buffered input stream positioned at start of archive
     * @return Optional Requires-Python value
     * @throws IOException On IO error
     */
    private static Optional<String> readZipBased(final InputStream input)
        throws IOException {
        try (ZipArchiveInputStream archive =
            new ZipArchiveInputStream(input)) {
            return scanArchive(archive);
        }
    }

    /**
     * Read Requires-Python from a tar.gz archive.
     *
     * @param input Buffered input stream positioned at start of archive
     * @return Optional Requires-Python value
     * @throws IOException On IO error
     */
    private static Optional<String> readTarGz(final InputStream input)
        throws IOException {
        try (GzipCompressorInputStream gzip =
            new GzipCompressorInputStream(input);
            TarArchiveInputStream archive =
                new TarArchiveInputStream(gzip)) {
            return scanArchive(archive);
        }
    }

    /**
     * Scan an open archive looking for PKG-INFO or METADATA entries and
     * extract the Requires-Python header.
     *
     * @param archive Open archive input stream (positioned at start)
     * @return Optional Requires-Python value; last match wins
     * @throws IOException On IO error
     * @checkstyle NestedIfDepthCheck (30 lines)
     */
    @SuppressWarnings("PMD.AssignmentInOperand")
    private static Optional<String> scanArchive(
        final ArchiveInputStream<?> archive
    ) throws IOException {
        ArchiveEntry entry;
        Optional<String> result = Optional.empty();
        while ((entry = archive.getNextEntry()) != null) {
            if (!archive.canReadEntryData(entry) || entry.isDirectory()) {
                continue;
            }
            final String entryName = entry.getName();
            if (entryName.contains("PKG-INFO")
                || entryName.contains("METADATA")) {
                final String content =
                    IOUtils.toString(archive, StandardCharsets.US_ASCII);
                result = parseRequiresPython(content);
            }
        }
        return result;
    }

    /**
     * Parse the {@code Requires-Python} header from metadata text.
     *
     * @param content PKG-INFO or METADATA file content
     * @return Optional constraint string, empty if the header is absent
     */
    private static Optional<String> parseRequiresPython(
        final String content
    ) {
        final String prefix = "Requires-Python:";
        return Stream.of(content.split("\n"))
            .filter(line -> line.startsWith(prefix))
            .findFirst()
            .map(line -> line.replace(prefix, "").trim())
            .filter(v -> !v.isEmpty());
    }

    /**
     * Return the last-modified time of a file as an {@link Instant}.
     *
     * @param path File path
     * @return Last-modified instant, or {@link Instant#now()} on error
     */
    private static Instant lastModifiedInstant(final Path path) {
        try {
            final BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.lastModifiedTime().toInstant();
        } catch (final IOException ex) {
            LOG.warn(
                "Cannot read last-modified time of {}: {}",
                path.getFileName(), ex.getMessage()
            );
            return Instant.now();
        }
    }

    /**
     * Write the sidecar JSON file to the filesystem.
     *
     * @param sidecar Target path for the JSON sidecar
     * @param requiresPython Requires-Python constraint (may be empty)
     * @param uploadTime Upload timestamp
     * @throws IOException On filesystem error
     */
    private static void writeSidecar(
        final Path sidecar,
        final String requiresPython,
        final Instant uploadTime
    ) throws IOException {
        Files.createDirectories(sidecar.getParent());
        final String rpJson;
        if (requiresPython == null || requiresPython.isEmpty()) {
            rpJson = "null";
        } else {
            rpJson = "\"" + requiresPython.replace("\"", "\\\"") + "\"";
        }
        final String json = String.format(
            "{\"requires-python\":%s,\"upload-time\":\"%s\","
                + "\"yanked\":false,\"yanked-reason\":null,"
                + "\"dist-info-metadata\":null}",
            rpJson,
            uploadTime.toString()
        );
        Files.writeString(sidecar, json, StandardCharsets.UTF_8);
    }
}
