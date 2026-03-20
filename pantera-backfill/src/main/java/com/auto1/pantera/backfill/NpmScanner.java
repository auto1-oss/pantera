/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for NPM repositories.
 *
 * <p>Supports two scanning modes:</p>
 * <ul>
 *   <li><b>Versions-directory mode (primary):</b> Walks for
 *       {@code .versions/} directories, reads version JSON files,
 *       resolves tarball sizes from sibling {@code -/} directories.
 *       Used for real Pantera NPM storage layout.</li>
 *   <li><b>Meta.json mode (fallback):</b> Walks for {@code meta.json}
 *       files, parses them to extract package name, versions, tarball
 *       sizes, and creation dates. Used for legacy/proxy layouts.</li>
 * </ul>
 *
 * @since 1.20.13
 */
final class NpmScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(NpmScanner.class);

    /**
     * Whether this is a proxy repository (used to determine path_prefix).
     * Pantera production always stores {@code "npm"} as repo_type for both
     * local and proxy NPM repositories; the proxy/local distinction is
     * captured solely through whether {@code path_prefix} is NULL or not.
     */
    private final boolean proxyMode;

    /**
     * Ctor for a local (hosted) NPM repository.
     */
    NpmScanner() {
        this(false);
    }

    /**
     * Ctor.
     *
     * @param proxyMode True if this is an npm-proxy repository
     */
    NpmScanner(final boolean proxyMode) {
        this.proxyMode = proxyMode;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final boolean hasVersionsDirs;
        try (Stream<Path> walk = Files.walk(root)) {
            hasVersionsDirs = walk
                .filter(Files::isDirectory)
                .anyMatch(
                    p -> ".versions".equals(
                        p.getFileName().toString()
                    )
                );
        }
        if (hasVersionsDirs) {
            return this.scanVersionsDirs(root, repoName);
        }
        LOG.info(
            "No .versions directories found, falling back to meta.json mode"
        );
        return this.scanMetaJson(root, repoName);
    }

    /**
     * Scan using .versions/ directories (Pantera NPM layout).
     *
     * <p>Layout for unscoped packages:</p>
     * <pre>
     * package-name/
     *   .versions/
     *     1.0.0.json
     *     1.0.1.json
     *   -/
     *     &#64;scope/
     *       package-name-1.0.0.tgz
     * </pre>
     *
     * <p>Layout for scoped packages:</p>
     * <pre>
     * &#64;scope/
     *   package-name/
     *     .versions/
     *       1.0.0.json
     *     -/
     *       &#64;scope/
     *         package-name-1.0.0.tgz
     * </pre>
     *
     * @param root Repository root
     * @param repoName Logical repository name
     * @return Stream of artifact records
     * @throws IOException If an I/O error occurs
     */
    private Stream<ArtifactRecord> scanVersionsDirs(final Path root,
        final String repoName) throws IOException {
        final List<ArtifactRecord> records = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(
                    p -> ".versions".equals(p.getFileName().toString())
                )
                .forEach(versionsDir -> {
                    final Path packageDir = versionsDir.getParent();
                    if (packageDir == null) {
                        return;
                    }
                    final String packageName = NpmScanner.resolvePackageName(
                        root, packageDir
                    );
                    if (packageName.isEmpty()) {
                        return;
                    }
                    try (Stream<Path> files = Files.list(versionsDir)) {
                        files.filter(Files::isRegularFile)
                            .filter(
                                f -> f.getFileName().toString()
                                    .endsWith(".json")
                            )
                            .forEach(jsonFile -> {
                                final String fname =
                                    jsonFile.getFileName().toString();
                                final String version = fname.substring(
                                    0, fname.length() - ".json".length()
                                );
                                if (version.isEmpty()) {
                                    return;
                                }
                                final Optional<Path> tgzOpt =
                                    NpmScanner.findTgzFile(
                                        packageDir, packageName, version
                                    );
                                final long size = tgzOpt.map(
                                    p -> {
                                        try {
                                            return Files.size(p);
                                        } catch (final IOException ex) {
                                            return 0L;
                                        }
                                    }
                                ).orElse(0L);
                                final String pathPrefix =
                                    this.proxyMode
                                    ? tgzOpt.map(
                                        p -> root.relativize(p)
                                            .toString().replace('\\', '/')
                                    ).orElse(null) : null;
                                final Long releaseDate = tgzOpt
                                    .flatMap(NpmScanner::readNpmReleaseDate)
                                    .orElse(null);
                                final long mtime =
                                    NpmScanner.fileMtime(jsonFile);
                                records.add(
                                    new ArtifactRecord(
                                        "npm",
                                        repoName,
                                        packageName,
                                        version,
                                        size,
                                        mtime,
                                        releaseDate,
                                        "system",
                                        pathPrefix
                                    )
                                );
                            });
                    } catch (final IOException ex) {
                        LOG.warn(
                            "Cannot list .versions dir {}: {}",
                            versionsDir, ex.getMessage()
                        );
                    }
                });
        }
        return records.stream();
    }

    /**
     * Resolve the NPM package name from the directory structure.
     * For scoped packages (@scope/name), the parent of packageDir
     * starts with {@code @}. For unscoped packages, packageDir
     * name is the full package name.
     *
     * @param root Repository root
     * @param packageDir Directory containing .versions/
     * @return Package name (e.g., "lodash" or "@scope/button")
     */
    private static String resolvePackageName(final Path root,
        final Path packageDir) {
        final Path relative = root.relativize(packageDir);
        final int count = relative.getNameCount();
        if (count == 0) {
            return "";
        }
        final String dirName = relative.getName(count - 1).toString();
        if (count >= 2) {
            final String parentName =
                relative.getName(count - 2).toString();
            if (parentName.startsWith("@")) {
                return parentName + "/" + dirName;
            }
        }
        return dirName;
    }

    /**
     * Find the tarball file for a given package version by searching
     * the {@code -/} subdirectory tree for a matching {@code .tgz} file.
     *
     * @param packageDir Package directory (parent of .versions/)
     * @param packageName Full package name
     * @param version Version string
     * @return Optional path to the tgz file, empty if not found
     */
    private static Optional<Path> findTgzFile(final Path packageDir,
        final String packageName, final String version) {
        final Path dashDir = packageDir.resolve("-");
        if (!Files.isDirectory(dashDir)) {
            return Optional.empty();
        }
        final String artifactName;
        final int slash = packageName.indexOf('/');
        if (slash >= 0) {
            artifactName = packageName.substring(slash + 1);
        } else {
            artifactName = packageName;
        }
        final String tgzName = artifactName + "-" + version + ".tgz";
        try (Stream<Path> walk = Files.walk(dashDir)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> tgzName.equals(p.getFileName().toString()))
                .findFirst();
        } catch (final IOException ex) {
            LOG.debug(
                "Cannot walk dash dir {}: {}", dashDir, ex.getMessage()
            );
            return Optional.empty();
        }
    }

    /**
     * Read the NPM release date from a tgz sidecar {@code .meta} file.
     *
     * <p>Pantera NPM proxy stores metadata alongside each cached tgz as
     * {@code {path}.meta}, a JSON file containing:
     * {@code {"last-modified":"RFC_1123_DATE","content-type":"..."}}.
     * The {@code last-modified} value is the {@code Last-Modified} HTTP
     * response header from the upstream NPM registry, which is the
     * package publish date — and the source of {@code release_date} in
     * production (via {@code NpmProxyPackageProcessor.releaseMillis()}).
     * </p>
     *
     * @param tgzPath Path to the {@code .tgz} file
     * @return Optional epoch millis, empty if sidecar is absent or unparseable
     */
    private static Optional<Long> readNpmReleaseDate(final Path tgzPath) {
        final Path metaPath = tgzPath.getParent()
            .resolve(tgzPath.getFileName().toString() + ".meta");
        if (!Files.isRegularFile(metaPath)) {
            return Optional.empty();
        }
        try (InputStream input = Files.newInputStream(metaPath);
            JsonReader reader = Json.createReader(input)) {
            final JsonObject json = reader.readObject();
            if (!json.containsKey("last-modified")
                || json.isNull("last-modified")) {
                return Optional.empty();
            }
            final String lm = json.getString("last-modified");
            return Optional.of(
                Instant.from(
                    DateTimeFormatter.RFC_1123_DATE_TIME.parse(lm)
                ).toEpochMilli()
            );
        } catch (final IOException | JsonException | DateTimeParseException ex) {
            LOG.debug(
                "Cannot read NPM release date from {}: {}",
                metaPath, ex.getMessage()
            );
            return Optional.empty();
        }
    }

    /**
     * Scan using meta.json files (legacy/fallback mode).
     *
     * @param root Repository root
     * @param repoName Logical repository name
     * @return Stream of artifact records
     * @throws IOException If an I/O error occurs
     */
    private Stream<ArtifactRecord> scanMetaJson(final Path root,
        final String repoName) throws IOException {
        return Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(
                path -> "meta.json".equals(path.getFileName().toString())
            )
            .flatMap(path -> this.parseMetaJson(root, repoName, path));
    }

    /**
     * Parse a single meta.json file and produce artifact records.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param metaPath Path to the meta.json file
     * @return Stream of artifact records, one per version
     */
    private Stream<ArtifactRecord> parseMetaJson(final Path root,
        final String repoName, final Path metaPath) {
        final JsonObject json;
        try (InputStream input = Files.newInputStream(metaPath);
            JsonReader reader = Json.createReader(input)) {
            json = reader.readObject();
        } catch (final JsonException ex) {
            LOG.warn("Malformed JSON in {}: {}", metaPath, ex.getMessage());
            return Stream.empty();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (!json.containsKey("name")
            || json.isNull("name")) {
            LOG.warn("Missing 'name' field in {}", metaPath);
            return Stream.empty();
        }
        final String packageName = json.getString("name");
        if (!json.containsKey("versions")
            || json.isNull("versions")
            || json.get("versions").getValueType()
            != JsonValue.ValueType.OBJECT) {
            LOG.warn(
                "Missing or invalid 'versions' field in {}", metaPath
            );
            return Stream.empty();
        }
        final JsonObject versions = json.getJsonObject("versions");
        final JsonObject time = json.containsKey("time")
            && !json.isNull("time")
            && json.get("time").getValueType()
            == JsonValue.ValueType.OBJECT
            ? json.getJsonObject("time") : null;
        final long metaMtime;
        try {
            metaMtime = Files.readAttributes(
                metaPath, BasicFileAttributes.class
            ).lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final List<ArtifactRecord> records = new ArrayList<>();
        for (final String version : versions.keySet()) {
            final Optional<Path> tarball = this.resolveMetaTarball(
                root, metaPath, versions.getJsonObject(version)
            );
            if (tarball.isEmpty()) {
                LOG.debug(
                    "Skipping {} {} — tarball not cached", packageName, version
                );
                continue;
            }
            final long size;
            try {
                size = Files.size(tarball.get());
            } catch (final IOException ex) {
                LOG.debug(
                    "Cannot stat tarball {}: {}",
                    tarball.get(), ex.getMessage()
                );
                continue;
            }
            final String pathPrefix = this.proxyMode
                ? root.relativize(tarball.get()).toString().replace('\\', '/')
                : null;
            final Long releaseDate =
                NpmScanner.readNpmReleaseDate(tarball.get()).orElse(null);
            final long createdDate = NpmScanner.resolveCreatedDate(
                time, version, metaMtime
            );
            records.add(
                new ArtifactRecord(
                    "npm",
                    repoName,
                    packageName,
                    version,
                    size,
                    createdDate,
                    releaseDate,
                    "system",
                    pathPrefix
                )
            );
        }
        return records.stream();
    }

    /**
     * Resolve the tarball path for a version entry from meta.json.
     *
     * @param root Repository root directory
     * @param metaPath Path to the meta.json file
     * @param versionObj Version metadata JSON object
     * @return Optional path to the tarball file, empty if not found
     */
    private Optional<Path> resolveMetaTarball(final Path root,
        final Path metaPath, final JsonObject versionObj) {
        if (!versionObj.containsKey("dist")
            || versionObj.isNull("dist")
            || versionObj.get("dist").getValueType()
            != JsonValue.ValueType.OBJECT) {
            return Optional.empty();
        }
        final JsonObject dist = versionObj.getJsonObject("dist");
        if (!dist.containsKey("tarball")
            || dist.isNull("tarball")
            || dist.get("tarball").getValueType()
            != JsonValue.ValueType.STRING) {
            return Optional.empty();
        }
        final String tarball =
            ((JsonString) dist.get("tarball")).getString();
        final String stripped = tarball.startsWith("/")
            ? tarball.substring(1) : tarball;
        final Path resolved = root.resolve(stripped);
        if (Files.isRegularFile(resolved)) {
            return Optional.of(resolved);
        }
        final Path filename = resolved.getFileName();
        if (filename != null) {
            final Path fallback =
                metaPath.getParent().resolve(filename.toString());
            if (Files.isRegularFile(fallback)) {
                return Optional.of(fallback);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the created date for a version from meta.json time field.
     *
     * @param time The "time" JSON object from the root, or null
     * @param version Version string to look up
     * @param metaMtime Meta.json file last-modified time in epoch millis
     * @return Created date as epoch millis
     */
    private static long resolveCreatedDate(final JsonObject time,
        final String version, final long metaMtime) {
        if (time != null && time.containsKey(version)
            && !time.isNull(version)
            && time.get(version).getValueType()
            == JsonValue.ValueType.STRING) {
            try {
                final String iso = time.getString(version);
                return Instant.parse(iso).toEpochMilli();
            } catch (final Exception ex) {
                LOG.debug(
                    "Cannot parse time for version {}: {}",
                    version, ex.getMessage()
                );
            }
        }
        return metaMtime;
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
