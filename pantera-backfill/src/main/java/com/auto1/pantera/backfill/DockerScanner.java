/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Docker v2 registry repositories.
 *
 * <p>Walks the Docker registry storage layout looking for image repositories
 * under {@code repositories/}, reads tag link files to resolve manifest
 * digests, and parses manifest JSON to compute artifact sizes.</p>
 *
 * @since 1.20.13
 */
final class DockerScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(DockerScanner.class);

    /**
     * Name of the manifests metadata directory.
     */
    private static final String MANIFESTS_DIR = "_manifests";

    /**
     * Name of the tags subdirectory.
     */
    private static final String TAGS_DIR = "tags";

    /**
     * Repository type string stored in every produced artifact record
     * (e.g. {@code "docker"} or {@code "docker-proxy"}).
     */
    private final String repoType;

    /**
     * When {@code true} this is a proxy repo — image names match the
     * upstream pull path with no prefix. When {@code false} (local/hosted)
     * the Pantera Docker push path includes the registry name in the image
     * path, so we prepend {@code repoName + "/"} to match production.
     */
    private final boolean isProxy;

    /**
     * Ctor for local (hosted) Docker repos.
     */
    DockerScanner() {
        this("docker", false);
    }

    /**
     * Ctor.
     *
     * @param isProxy {@code true} for proxy repos, {@code false} for local
     */
    DockerScanner(final boolean isProxy) {
        this(isProxy ? "docker-proxy" : "docker", isProxy);
    }

    /**
     * Ctor.
     *
     * @param repoType Repository type string for artifact records
     * @param isProxy {@code true} for proxy repos, {@code false} for local
     */
    DockerScanner(final String repoType, final boolean isProxy) {
        this.repoType = repoType;
        this.isProxy = isProxy;
    }

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Path reposDir = DockerScanner.resolveReposDir(root);
        if (reposDir == null) {
            LOG.warn("No repositories directory found under {}", root);
            return Stream.empty();
        }
        final Path blobsRoot = reposDir.getParent().resolve("blobs");
        final List<Path> images = DockerScanner.findImages(reposDir);
        final List<ArtifactRecord> records = new ArrayList<>();
        for (final Path imageDir : images) {
            final String rawImageName =
                reposDir.relativize(imageDir).toString();
            final String imageName = this.isProxy
                ? rawImageName
                : repoName + "/" + rawImageName;
            final Path tagsDir = imageDir
                .resolve(DockerScanner.MANIFESTS_DIR)
                .resolve(DockerScanner.TAGS_DIR);
            if (!Files.isDirectory(tagsDir)) {
                continue;
            }
            try (Stream<Path> tagDirs = Files.list(tagsDir)) {
                final List<Path> tagList = tagDirs
                    .filter(Files::isDirectory)
                    .toList();
                for (final Path tagDir : tagList) {
                    final ArtifactRecord record = this.processTag(
                        blobsRoot, repoName, imageName, tagDir
                    );
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        }
        return records.stream();
    }

    /**
     * Resolve the repositories directory. Checks common Docker registry
     * v2 layouts:
     * <ul>
     *   <li>{@code root/repositories/}</li>
     *   <li>{@code root/docker/registry/v2/repositories/}</li>
     * </ul>
     * Falls back to walking for a directory named {@code repositories}
     * that contains image dirs with {@code _manifests/}.
     *
     * @param root Registry root path
     * @return Path to the repositories directory, or null if not found
     * @throws IOException If an I/O error occurs during directory walk
     */
    private static Path resolveReposDir(final Path root) throws IOException {
        final Path direct = root.resolve("repositories");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        final Path v2 = root.resolve("docker/registry/v2/repositories");
        if (Files.isDirectory(v2)) {
            return v2;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isDirectory)
                .filter(
                    p -> "repositories".equals(p.getFileName().toString())
                )
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * Walk the repositories directory to find all image directories.
     * An image directory is one that contains {@code _manifests/tags/}.
     *
     * @param reposDir The repositories root directory
     * @return List of image directory paths
     * @throws IOException If an I/O error occurs
     */
    private static List<Path> findImages(final Path reposDir)
        throws IOException {
        final List<Path> images = new ArrayList<>();
        try (Stream<Path> walker = Files.walk(reposDir)) {
            walker.filter(Files::isDirectory)
                .filter(
                    dir -> {
                        final Path manifests = dir
                            .resolve(DockerScanner.MANIFESTS_DIR)
                            .resolve(DockerScanner.TAGS_DIR);
                        return Files.isDirectory(manifests);
                    }
                )
                .forEach(images::add);
        }
        return images;
    }

    /**
     * Process a single tag directory and produce an artifact record.
     *
     * @param blobsRoot Path to the blobs directory
     * @param repoName Logical repository name
     * @param imageName Image name (relative path from repositories dir)
     * @param tagDir Tag directory path
     * @return ArtifactRecord, or null if tag should be skipped
     */
    private ArtifactRecord processTag(final Path blobsRoot,
        final String repoName, final String imageName, final Path tagDir) {
        final String tag = tagDir.getFileName().toString();
        final Path linkFile = tagDir.resolve("current").resolve("link");
        if (!Files.isRegularFile(linkFile)) {
            LOG.debug("No link file at {}", linkFile);
            return null;
        }
        final String digest;
        try {
            digest = Files.readString(linkFile, StandardCharsets.UTF_8).trim();
        } catch (final IOException ex) {
            LOG.warn("Cannot read link file {}: {}", linkFile, ex.getMessage());
            return null;
        }
        if (digest.isEmpty()) {
            LOG.debug("Empty link file at {}", linkFile);
            return null;
        }
        final long createdDate = DockerScanner.linkMtime(linkFile);
        final long size = DockerScanner.resolveSize(blobsRoot, digest);
        return new ArtifactRecord(
            this.repoType,
            repoName,
            imageName,
            tag,
            size,
            createdDate,
            null,
            "system",
            null
        );
    }

    /**
     * Resolve the total size of an artifact from its manifest digest.
     * For image manifests with layers, sums config.size + layers[].size.
     * For manifest lists, uses the manifest blob file's own size.
     * Returns 0 if the blob is missing or manifest is corrupt.
     *
     * @param blobsRoot Path to the blobs directory
     * @param digest Digest string like "sha256:abc123..."
     * @return Total size in bytes
     */
    private static long resolveSize(final Path blobsRoot,
        final String digest) {
        final Path blobPath = DockerScanner.digestToPath(blobsRoot, digest);
        if (blobPath == null || !Files.isRegularFile(blobPath)) {
            LOG.debug("Blob not found for digest {}", digest);
            return 0L;
        }
        final JsonObject manifest;
        try (InputStream input = Files.newInputStream(blobPath);
            JsonReader reader = Json.createReader(input)) {
            manifest = reader.readObject();
        } catch (final JsonException ex) {
            LOG.warn(
                "Corrupted manifest JSON for digest {}: {}",
                digest, ex.getMessage()
            );
            return 0L;
        } catch (final IOException ex) {
            LOG.warn("Cannot read blob {}: {}", blobPath, ex.getMessage());
            return 0L;
        }
        if (manifest.containsKey("manifests")
            && manifest.get("manifests").getValueType()
            == JsonValue.ValueType.ARRAY) {
            return DockerScanner.resolveManifestListSize(
                blobsRoot, manifest.getJsonArray("manifests")
            );
        }
        return DockerScanner.sumLayersAndConfig(manifest);
    }

    /**
     * Sum config.size and all layers[].size from an image manifest.
     *
     * @param manifest Parsed manifest JSON object
     * @return Total size in bytes, or 0 if fields are missing
     */
    private static long sumLayersAndConfig(final JsonObject manifest) {
        long total = 0L;
        if (manifest.containsKey("config")
            && manifest.get("config").getValueType()
            == JsonValue.ValueType.OBJECT) {
            final JsonObject config = manifest.getJsonObject("config");
            if (config.containsKey("size")) {
                total += config.getJsonNumber("size").longValue();
            }
        }
        if (manifest.containsKey("layers")
            && manifest.get("layers").getValueType()
            == JsonValue.ValueType.ARRAY) {
            final JsonArray layers = manifest.getJsonArray("layers");
            for (final JsonValue layer : layers) {
                if (layer.getValueType() == JsonValue.ValueType.OBJECT) {
                    final JsonObject layerObj = layer.asJsonObject();
                    if (layerObj.containsKey("size")) {
                        total += layerObj.getJsonNumber("size").longValue();
                    }
                }
            }
        }
        return total;
    }

    /**
     * Resolve the total size of a manifest list by summing the sizes
     * of all child image manifests' layers and configs.
     *
     * @param blobsRoot Path to the blobs directory
     * @param children The "manifests" JSON array from the manifest list
     * @return Total size in bytes across all child manifests
     */
    private static long resolveManifestListSize(final Path blobsRoot,
        final JsonArray children) {
        long total = 0L;
        for (final JsonValue entry : children) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject child = entry.asJsonObject();
            final String childDigest = child.getString("digest", null);
            if (childDigest == null || childDigest.isEmpty()) {
                continue;
            }
            final Path childPath =
                DockerScanner.digestToPath(blobsRoot, childDigest);
            if (childPath == null || !Files.isRegularFile(childPath)) {
                LOG.debug("Child manifest blob not found: {}", childDigest);
                continue;
            }
            try (InputStream input = Files.newInputStream(childPath);
                JsonReader reader = Json.createReader(input)) {
                final JsonObject childManifest = reader.readObject();
                total += DockerScanner.sumLayersAndConfig(childManifest);
            } catch (final JsonException | IOException ex) {
                LOG.warn("Cannot read child manifest {}: {}",
                    childDigest, ex.getMessage());
            }
        }
        return total;
    }

    /**
     * Convert a digest string to a blob file path.
     *
     * @param blobsRoot Root blobs directory
     * @param digest Digest like "sha256:abc123def..."
     * @return Path to the data file, or null if digest format is invalid
     */
    private static Path digestToPath(final Path blobsRoot,
        final String digest) {
        final String[] parts = digest.split(":", 2);
        if (parts.length != 2 || parts[1].length() < 2) {
            LOG.warn("Invalid digest format: {}", digest);
            return null;
        }
        final String algorithm = parts[0];
        final String hex = parts[1];
        return blobsRoot
            .resolve(algorithm)
            .resolve(hex.substring(0, 2))
            .resolve(hex)
            .resolve("data");
    }

    /**
     * Get the last-modified time of the link file as epoch millis.
     *
     * @param linkFile Path to the link file
     * @return Epoch millis
     */
    private static long linkMtime(final Path linkFile) {
        try {
            return Files.readAttributes(linkFile, BasicFileAttributes.class)
                .lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            LOG.debug(
                "Cannot read mtime of {}: {}", linkFile, ex.getMessage()
            );
            return System.currentTimeMillis();
        }
    }
}
