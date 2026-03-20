/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Composer (PHP) repositories.
 *
 * <p>Supports two layouts:</p>
 * <ul>
 *   <li><strong>p2 (Satis-style)</strong>: per-package JSON files under
 *       {@code p2/{vendor}/{package}.json}. Files ending with {@code ~dev.json}
 *       are skipped.</li>
 *   <li><strong>packages.json</strong>: a single root-level file containing
 *       all package metadata.</li>
 * </ul>
 *
 * <p>The p2 layout is checked first; if the {@code p2/} directory exists,
 * {@code packages.json} is ignored even if present.</p>
 *
 * @since 1.20.13
 */
final class ComposerScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(ComposerScanner.class);

    /**
     * Repository type string stored in every produced artifact record
     * (e.g. {@code "composer"} or {@code "php"}).
     */
    private final String repoType;

    /**
     * Ctor with default repo type {@code "composer"}.
     */
    ComposerScanner() {
        this("composer");
    }

    /**
     * Ctor.
     *
     * @param repoType Repository type string for artifact records
     */
    ComposerScanner(final String repoType) {
        this.repoType = repoType;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Path p2dir = root.resolve("p2");
        if (Files.isDirectory(p2dir)) {
            return this.scanP2(root, repoName, p2dir);
        }
        final Path packagesJson = root.resolve("packages.json");
        if (Files.isRegularFile(packagesJson) && Files.size(packagesJson) > 0) {
            final List<ArtifactRecord> from =
                this.parseJsonFile(root, repoName, packagesJson)
                    .collect(Collectors.toList());
            if (!from.isEmpty()) {
                return from.stream();
            }
            LOG.debug(
                "packages.json has no packages, trying vendor-dir layout"
            );
        }
        return this.scanVendorDirs(root, repoName);
    }

    /**
     * Scan the p2 directory layout. Walks all {@code .json} files under
     * {@code p2/}, skipping any that end with {@code ~dev.json}.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param p2dir Path to the p2 directory
     * @return Stream of artifact records
     * @throws IOException If an I/O error occurs
     */
    private Stream<ArtifactRecord> scanP2(final Path root,
        final String repoName, final Path p2dir) throws IOException {
        return Files.walk(p2dir)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".json"))
            .filter(path -> !path.getFileName().toString().endsWith("~dev.json"))
            .flatMap(path -> this.parseJsonFile(root, repoName, path));
    }

    /**
     * Scan the Pantera Composer proxy layout.
     *
     * <p>The Pantera Composer proxy caches per-package metadata as
     * {@code {vendor}/{package}.json} files directly under the repository
     * root (no {@code p2/} prefix). Each file uses the standard Composer
     * {@code {"packages":{...}}} JSON format.</p>
     *
     * <p>Files ending with {@code ~dev.json} and 0-byte files are skipped.</p>
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @return Stream of artifact records
     * @throws IOException If an I/O error occurs
     */
    private Stream<ArtifactRecord> scanVendorDirs(final Path root,
        final String repoName) throws IOException {
        return Files.list(root)
            .filter(Files::isDirectory)
            .filter(dir -> !dir.getFileName().toString().startsWith("."))
            .flatMap(
                vendorDir -> {
                    try {
                        return Files.list(vendorDir)
                            .filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".json"))
                            .filter(
                                path -> !path.getFileName().toString().endsWith("~dev.json")
                            )
                            .filter(
                                path -> {
                                    try {
                                        return Files.size(path) > 0L;
                                    } catch (final IOException ex) {
                                        LOG.debug("Cannot stat {}, skipping: {}", path, ex.getMessage());
                                        return false;
                                    }
                                }
                            )
                            .flatMap(path -> this.parseJsonFile(root, repoName, path));
                    } catch (final IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            );
    }

    /**
     * Parse a single Composer JSON file and produce artifact records.
     *
     * @param root Repository root directory
     * @param repoName Logical repository name
     * @param jsonPath Path to the JSON file
     * @return Stream of artifact records
     */
    private Stream<ArtifactRecord> parseJsonFile(final Path root,
        final String repoName, final Path jsonPath) {
        final JsonObject json;
        try (InputStream input = Files.newInputStream(jsonPath);
            JsonReader reader = Json.createReader(input)) {
            json = reader.readObject();
        } catch (final JsonException ex) {
            LOG.warn("Malformed JSON in {}: {}", jsonPath, ex.getMessage());
            return Stream.empty();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (!json.containsKey("packages")
            || json.isNull("packages")
            || json.get("packages").getValueType() != JsonValue.ValueType.OBJECT) {
            LOG.debug("Missing or invalid 'packages' key in {}", jsonPath);
            return Stream.empty();
        }
        final JsonObject packages = json.getJsonObject("packages");
        final long mtime;
        try {
            mtime = Files.readAttributes(jsonPath, BasicFileAttributes.class)
                .lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final boolean proxyMode = this.repoType.endsWith("-proxy");
        final List<ArtifactRecord> records = new ArrayList<>();
        for (final String packageName : packages.keySet()) {
            if (packages.isNull(packageName)
                || packages.get(packageName).getValueType()
                != JsonValue.ValueType.OBJECT) {
                LOG.debug("Skipping non-object package entry: {}", packageName);
                continue;
            }
            final JsonObject versions = packages.getJsonObject(packageName);
            for (final String version : versions.keySet()) {
                if (versions.isNull(version)
                    || versions.get(version).getValueType()
                    != JsonValue.ValueType.OBJECT) {
                    LOG.debug(
                        "Skipping non-object version entry: {} {}",
                        packageName, version
                    );
                    continue;
                }
                final JsonObject versionObj = versions.getJsonObject(version);
                // For proxy repos, only record versions that have cached
                // dist artifacts on disk. The metadata JSON lists all upstream
                // versions but only downloaded ones have actual files.
                // Check both .zip (new format) and plain (legacy).
                if (proxyMode) {
                    final Path distDir = root.resolve("dist")
                        .resolve(packageName);
                    final Path zipFile = distDir.resolve(version + ".zip");
                    final Path legacyFile = distDir.resolve(version);
                    if (!Files.exists(zipFile) && !Files.exists(legacyFile)) {
                        continue;
                    }
                }
                long size = ComposerScanner.resolveDistSize(
                    root, versionObj
                );
                // For proxy repos, if dist URL resolution failed, read size
                // directly from the cached file on disk
                if (size == 0L && proxyMode) {
                    final Path distDir = root.resolve("dist")
                        .resolve(packageName);
                    final Path zipFile = distDir.resolve(version + ".zip");
                    final Path legacyFile = distDir.resolve(version);
                    try {
                        if (Files.isRegularFile(zipFile)) {
                            size = Files.size(zipFile);
                        } else if (Files.isRegularFile(legacyFile)) {
                            size = Files.size(legacyFile);
                        }
                    } catch (final IOException ignored) {
                        // keep size = 0
                    }
                }
                final String pathPrefix = proxyMode
                    ? packageName + "/" + version : null;
                records.add(
                    new ArtifactRecord(
                        this.repoType,
                        repoName,
                        packageName,
                        version,
                        size,
                        mtime,
                        null,
                        "system",
                        pathPrefix
                    )
                );
            }
        }
        return records.stream();
    }

    /**
     * Resolve the dist artifact size for a version entry.
     *
     * <p>Tries to extract the {@code dist.url} field and resolve it as a
     * local file path. For HTTP URLs the path component is extracted and
     * attempted relative to the repository root. If the file cannot be
     * found the size is 0.</p>
     *
     * @param root Repository root directory
     * @param versionObj Version metadata JSON object
     * @return Size in bytes, or 0 if the artifact cannot be found
     */
    private static long resolveDistSize(final Path root,
        final JsonObject versionObj) {
        if (!versionObj.containsKey("dist")
            || versionObj.isNull("dist")
            || versionObj.get("dist").getValueType()
            != JsonValue.ValueType.OBJECT) {
            return 0L;
        }
        final JsonObject dist = versionObj.getJsonObject("dist");
        if (!dist.containsKey("url")
            || dist.isNull("url")
            || dist.get("url").getValueType() != JsonValue.ValueType.STRING) {
            return 0L;
        }
        final String url = dist.getString("url");
        return ComposerScanner.sizeFromUrl(root, url);
    }

    /**
     * Attempt to resolve a dist URL to a local file and return its size.
     *
     * @param root Repository root directory
     * @param url The dist URL string
     * @return File size in bytes, or 0 if the file is not found
     */
    private static long sizeFromUrl(final Path root, final String url) {
        String localPath;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                localPath = URI.create(url).getPath();
            } catch (final IllegalArgumentException ex) {
                LOG.debug("Cannot parse dist URL '{}': {}", url, ex.getMessage());
                return 0L;
            }
        } else {
            localPath = url;
        }
        if (localPath == null || localPath.isEmpty()) {
            return 0L;
        }
        if (localPath.startsWith("/")) {
            localPath = localPath.substring(1);
        }
        final Path resolved = root.resolve(localPath);
        if (Files.isRegularFile(resolved)) {
            try {
                return Files.size(resolved);
            } catch (final IOException ex) {
                LOG.debug("Cannot stat {}: {}", resolved, ex.getMessage());
                return 0L;
            }
        }
        final int lastSlash = localPath.lastIndexOf('/');
        if (lastSlash >= 0) {
            final String filename = localPath.substring(lastSlash + 1);
            final Path fallback = root.resolve(filename);
            if (Files.isRegularFile(fallback)) {
                try {
                    return Files.size(fallback);
                } catch (final IOException ex) {
                    LOG.debug(
                        "Cannot stat fallback {}: {}",
                        fallback, ex.getMessage()
                    );
                    return 0L;
                }
            }
        }
        // Final fallback: progressively strip leading path segments.
        // Handles Pantera local PHP repos where the dist URL contains
        // a full HTTP path like "/prefix/api/composer/repo/artifacts/...".
        String stripped = localPath;
        while (stripped.contains("/")) {
            stripped = stripped.substring(stripped.indexOf('/') + 1);
            if (stripped.isEmpty()) {
                break;
            }
            final Path candidate = root.resolve(stripped);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.size(candidate);
                } catch (final IOException ex) {
                    LOG.debug(
                        "Cannot stat candidate {}: {}",
                        candidate, ex.getMessage()
                    );
                    return 0L;
                }
            }
        }
        return 0L;
    }
}
