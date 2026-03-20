/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Go module repositories.
 *
 * <p>Walks every {@code @v} directory in the tree. For each one:</p>
 * <ul>
 *   <li>If a {@code list} file is present, versions are read from it
 *       and the corresponding {@code .zip} files are resolved.</li>
 *   <li>Otherwise, all {@code .zip} files in the directory are
 *       enumerated directly. The paired {@code .info} file is used
 *       for date resolution when available.</li>
 * </ul>
 * <p>This per-directory dispatch ensures proxy repos where some modules
 * have a {@code list} file and others do not are both captured.</p>
 *
 * @since 1.20.13
 */
final class GoScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(GoScanner.class);

    /**
     * Repository type string stored in every produced artifact record
     * (e.g. {@code "go"} or {@code "go-proxy"}).
     */
    private final String repoType;

    /**
     * Ctor with default repo type {@code "go"}.
     */
    GoScanner() {
        this("go");
    }

    /**
     * Ctor.
     *
     * @param repoType Repository type string for artifact records
     */
    GoScanner(final String repoType) {
        this.repoType = repoType;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final List<ArtifactRecord> records = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(p -> "@v".equals(p.getFileName().toString()))
                .forEach(atVDir -> {
                    final Path listFile = atVDir.resolve("list");
                    if (Files.isRegularFile(listFile)) {
                        this.processListFile(root, repoName, listFile)
                            .forEach(records::add);
                    } else {
                        this.processZipDir(root, repoName, atVDir)
                            .forEach(records::add);
                    }
                });
        }
        return records.stream();
    }

    /**
     * Enumerate {@code .zip} files in an {@code @v} directory that has no
     * {@code list} file (proxy-cached module with no version list).
     *
     * <p>The paired {@code .info} file is used for date resolution when
     * present; falls back to the zip file mtime.</p>
     *
     * @param root Repository root
     * @param repoName Logical repository name
     * @param atVDir The {@code @v} directory to scan
     * @return Stream of artifact records
     */
    private Stream<ArtifactRecord> processZipDir(final Path root,
        final String repoName, final Path atVDir) {
        final Path moduleDir = atVDir.getParent();
        final String modulePath = root.relativize(moduleDir)
            .toString().replace('\\', '/');
        final List<ArtifactRecord> records = new ArrayList<>();
        try (Stream<Path> dirStream = Files.list(atVDir)) {
            dirStream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .forEach(zipFile -> {
                    final String fname = zipFile.getFileName().toString();
                    final String version = fname.substring(
                        0, fname.length() - ".zip".length()
                    );
                    if (version.isEmpty()) {
                        return;
                    }
                    final long createdDate = GoScanner.resolveCreatedDate(
                        atVDir, version, GoScanner.fileMtime(zipFile)
                    );
                    final long size = GoScanner.resolveZipSize(atVDir, version);
                    final String stripped = GoScanner.stripV(version);
                    final String pathPrefix = this.repoType.endsWith("-proxy")
                        ? modulePath + "/@v/" + stripped : null;
                    records.add(new ArtifactRecord(
                        this.repoType, repoName, modulePath, stripped,
                        size, createdDate, null, "system", pathPrefix
                    ));
                });
        } catch (final IOException ex) {
            LOG.debug("Cannot list @v dir {}: {}", atVDir, ex.getMessage());
        }
        return records.stream();
    }

    /**
     * Process a single {@code @v/list} file and produce artifact records
     * for every version listed inside it.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param listFile Path to the {@code @v/list} file
     * @return Stream of artifact records, one per version
     */
    private Stream<ArtifactRecord> processListFile(final Path root,
        final String repoName, final Path listFile) {
        final Path atVDir = listFile.getParent();
        final Path moduleDir = atVDir.getParent();
        final String modulePath = root.relativize(moduleDir).toString()
            .replace('\\', '/');
        final List<String> lines;
        try {
            lines = Files.readAllLines(listFile);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final long listMtime = GoScanner.fileMtime(listFile);
        final List<ArtifactRecord> records = new ArrayList<>();
        final boolean hasVersions =
            lines.stream().anyMatch(l -> !l.trim().isEmpty());
        if (hasVersions) {
            for (final String line : lines) {
                final String version = line.trim();
                if (version.isEmpty()) {
                    continue;
                }
                final Path zipFile = atVDir.resolve(
                    String.format("%s.zip", version)
                );
                if (!Files.isRegularFile(zipFile)) {
                    LOG.debug(
                        "Skipping {} {} — zip not cached", modulePath, version
                    );
                    continue;
                }
                final long createdDate = GoScanner.resolveCreatedDate(
                    atVDir, version, listMtime
                );
                final long size = GoScanner.resolveZipSize(atVDir, version);
                final String stripped = GoScanner.stripV(version);
                final String pathPrefix = this.repoType.endsWith("-proxy")
                    ? modulePath + "/@v/" + stripped : null;
                records.add(
                    new ArtifactRecord(
                        this.repoType,
                        repoName,
                        modulePath,
                        stripped,
                        size,
                        createdDate,
                        null,
                        "system",
                        pathPrefix
                    )
                );
            }
        } else {
            // Empty list file — scan @v directory directly for .zip files.
            // Proxy-cached modules where only a specific version was fetched
            // (no list request) will have an empty list but a present .zip.
            try (Stream<Path> dirStream = Files.list(atVDir)) {
                dirStream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .forEach(zipFile -> {
                        final String fname = zipFile.getFileName().toString();
                        final String ver = fname.substring(
                            0, fname.length() - ".zip".length()
                        );
                        if (ver.isEmpty()) {
                            return;
                        }
                        final long createdDate = GoScanner.resolveCreatedDate(
                            atVDir, ver, listMtime
                        );
                        final long size =
                            GoScanner.resolveZipSize(atVDir, ver);
                        final String stripped = GoScanner.stripV(ver);
                        final String pathPrefix = this.repoType.endsWith("-proxy")
                            ? modulePath + "/@v/" + stripped : null;
                        records.add(new ArtifactRecord(
                            this.repoType, repoName, modulePath, stripped,
                            size, createdDate, null, "system", pathPrefix
                        ));
                    });
            } catch (final IOException ex) {
                LOG.debug(
                    "Cannot list @v dir {}: {}", atVDir, ex.getMessage()
                );
            }
        }
        return records.stream();
    }

    /**
     * Resolve the creation date for a version. Reads the {@code .info} JSON
     * file and parses the {@code "Time"} field. Falls back to the list file
     * mtime if the {@code .info} file is missing or cannot be parsed.
     *
     * @param atVDir Path to the {@code @v} directory
     * @param version Version string (e.g. {@code v1.0.0})
     * @param fallback Fallback epoch millis (list file mtime)
     * @return Epoch millis
     */
    private static long resolveCreatedDate(final Path atVDir,
        final String version, final long fallback) {
        final Path infoFile = atVDir.resolve(
            String.format("%s.info", version)
        );
        if (!Files.isRegularFile(infoFile)) {
            return fallback;
        }
        try (InputStream input = Files.newInputStream(infoFile);
            JsonReader reader = Json.createReader(input)) {
            final JsonObject json = reader.readObject();
            if (json.containsKey("Time") && !json.isNull("Time")) {
                final String time = json.getString("Time");
                return Instant.parse(time).toEpochMilli();
            }
        } catch (final JsonException ex) {
            LOG.warn(
                "Invalid JSON in {}: {}", infoFile, ex.getMessage()
            );
        } catch (final Exception ex) {
            LOG.warn(
                "Cannot parse .info file {}: {}", infoFile, ex.getMessage()
            );
        }
        return fallback;
    }

    /**
     * Resolve the zip file size for a version. Returns 0 if the zip
     * file does not exist.
     *
     * @param atVDir Path to the {@code @v} directory
     * @param version Version string (e.g. {@code v1.0.0})
     * @return File size in bytes, or 0 if not found
     */
    private static long resolveZipSize(final Path atVDir,
        final String version) {
        final Path zipFile = atVDir.resolve(
            String.format("%s.zip", version)
        );
        if (Files.isRegularFile(zipFile)) {
            try {
                return Files.size(zipFile);
            } catch (final IOException ex) {
                LOG.debug(
                    "Cannot stat zip file {}: {}", zipFile, ex.getMessage()
                );
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Strip the leading {@code v} prefix from a Go version string.
     * The Go adapter stores versions without the {@code v} prefix
     * (e.g. {@code 1.0.0} instead of {@code v1.0.0}).
     *
     * @param version Version string, possibly starting with "v"
     * @return Version without "v" prefix
     */
    private static String stripV(final String version) {
        if (version.startsWith("v") || version.startsWith("V")) {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Get the last-modified time of a file as epoch millis.
     *
     * @param path Path to the file
     * @return Epoch millis
     */
    private static long fileMtime(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class)
                .lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            LOG.debug(
                "Cannot read mtime of {}: {}", path, ex.getMessage()
            );
            return System.currentTimeMillis();
        }
    }
}
