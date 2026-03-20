/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Debian repositories.
 *
 * <p>Walks the repository directory tree to find {@code Packages} and
 * {@code Packages.gz} index files under the standard Debian layout
 * ({@code dists/{codename}/{component}/binary-{arch}/}). Each stanza
 * in a Packages file describes one {@code .deb} package. The scanner
 * extracts the {@code Package}, {@code Version}, and {@code Size}
 * fields from each stanza.</p>
 *
 * <p>When both {@code Packages} and {@code Packages.gz} exist in the
 * same directory, only {@code Packages.gz} is used to avoid
 * double-counting.</p>
 *
 * @since 1.20.13
 */
final class DebianScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(DebianScanner.class);

    /**
     * Name of the uncompressed Packages index file.
     */
    private static final String PACKAGES = "Packages";

    /**
     * Name of the gzip-compressed Packages index file.
     */
    private static final String PACKAGES_GZ = "Packages.gz";

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final List<Path> indexFiles = Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(DebianScanner::isPackagesFile)
            .collect(Collectors.toList());
        final List<Path> deduped = DebianScanner.dedup(indexFiles);
        return deduped.stream()
            .flatMap(path -> DebianScanner.parseIndex(path, repoName));
    }

    /**
     * Check whether a file is a Packages or Packages.gz index file.
     *
     * @param path File path to check
     * @return True if the filename is "Packages" or "Packages.gz"
     */
    private static boolean isPackagesFile(final Path path) {
        final String name = path.getFileName().toString();
        return PACKAGES.equals(name) || PACKAGES_GZ.equals(name);
    }

    /**
     * Deduplicate index files by parent directory.
     * When both Packages and Packages.gz exist in the same directory,
     * prefer Packages.gz.
     *
     * @param files List of discovered index files
     * @return Deduplicated list preferring .gz files
     */
    private static List<Path> dedup(final List<Path> files) {
        final Map<Path, Path> byParent = new HashMap<>();
        for (final Path file : files) {
            final Path parent = file.getParent();
            final Path existing = byParent.get(parent);
            if (existing == null) {
                byParent.put(parent, file);
            } else if (file.getFileName().toString().equals(PACKAGES_GZ)) {
                byParent.put(parent, file);
            }
        }
        return new ArrayList<>(byParent.values());
    }

    /**
     * Parse a single Packages or Packages.gz file into artifact records.
     *
     * @param path Path to the index file
     * @param repoName Logical repository name
     * @return Stream of artifact records parsed from the index
     */
    private static Stream<ArtifactRecord> parseIndex(final Path path,
        final String repoName) {
        try {
            final long mtime = Files.getLastModifiedTime(path).toMillis();
            final List<ArtifactRecord> records = new ArrayList<>();
            try (
                InputStream fis = Files.newInputStream(path);
                InputStream input = path.getFileName().toString().equals(PACKAGES_GZ)
                    ? new GZIPInputStream(fis) : fis;
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
                )
            ) {
                String pkg = null;
                String version = null;
                String arch = null;
                long size = 0L;
                String line = reader.readLine();
                while (line != null) {
                    if (line.isEmpty()) {
                        if (pkg != null && version != null) {
                            records.add(
                                new ArtifactRecord(
                                    "deb",
                                    repoName,
                                    DebianScanner.formatName(pkg, arch),
                                    version,
                                    size,
                                    mtime,
                                    null,
                                    "system",
                                    null
                                )
                            );
                        } else if (pkg != null || version != null) {
                            LOG.debug(
                                "Skipping incomplete stanza (Package={}, Version={}) in {}",
                                pkg, version, path
                            );
                        }
                        pkg = null;
                        version = null;
                        arch = null;
                        size = 0L;
                    } else if (line.startsWith("Package:")) {
                        pkg = line.substring("Package:".length()).trim();
                    } else if (line.startsWith("Version:")) {
                        version = line.substring("Version:".length()).trim();
                    } else if (line.startsWith("Architecture:")) {
                        arch = line.substring("Architecture:".length()).trim();
                    } else if (line.startsWith("Size:")) {
                        try {
                            size = Long.parseLong(
                                line.substring("Size:".length()).trim()
                            );
                        } catch (final NumberFormatException ex) {
                            LOG.debug(
                                "Invalid Size value in {}: {}",
                                path, line
                            );
                            size = 0L;
                        }
                    }
                    line = reader.readLine();
                }
                if (pkg != null && version != null) {
                    records.add(
                        new ArtifactRecord(
                            "deb",
                            repoName,
                            DebianScanner.formatName(pkg, arch),
                            version,
                            size,
                            mtime,
                            null,
                            "system",
                            null
                        )
                    );
                }
            }
            return records.stream();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Format the artifact name. The Debian adapter stores artifact names
     * as {@code package_architecture} (e.g. {@code curl_amd64}).
     * If architecture is missing, uses just the package name.
     *
     * @param pkg Package name
     * @param arch Architecture string, or null if not present
     * @return Formatted name
     */
    private static String formatName(final String pkg, final String arch) {
        if (arch != null && !arch.isEmpty()) {
            return String.join("_", pkg, arch);
        }
        return pkg;
    }
}
