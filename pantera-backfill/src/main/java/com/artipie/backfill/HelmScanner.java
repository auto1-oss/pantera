/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Scanner for Helm chart repositories.
 *
 * <p>Reads {@code index.yaml} from the repository root, parses it with
 * SnakeYAML, and emits one {@link ArtifactRecord} per chart version.
 * The {@code .tgz} file referenced in the {@code urls} list is resolved
 * relative to the root directory to determine artifact size.</p>
 *
 * @since 1.20.13
 */
final class HelmScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(HelmScanner.class);

    @Override
    @SuppressWarnings("unchecked")
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        final Path indexPath = root.resolve("index.yaml");
        if (!Files.isRegularFile(indexPath)) {
            LOG.debug("No index.yaml found in {}", root);
            return Stream.empty();
        }
        final Map<String, Object> index;
        try (InputStream input = Files.newInputStream(indexPath)) {
            index = new Yaml().load(input);
        }
        if (index == null || !index.containsKey("entries")) {
            LOG.debug("No 'entries' key in index.yaml at {}", indexPath);
            return Stream.empty();
        }
        final Object entriesObj = index.get("entries");
        if (!(entriesObj instanceof Map)) {
            LOG.warn("'entries' is not a map in {}", indexPath);
            return Stream.empty();
        }
        final Map<String, Object> entries = (Map<String, Object>) entriesObj;
        final long indexMtime = HelmScanner.indexMtime(indexPath);
        final List<ArtifactRecord> records = new ArrayList<>();
        for (final Map.Entry<String, Object> entry : entries.entrySet()) {
            final String chartName = entry.getKey();
            final Object versionsObj = entry.getValue();
            if (!(versionsObj instanceof List)) {
                LOG.debug("Skipping chart {} with non-list versions", chartName);
                continue;
            }
            final List<Map<String, Object>> versionsList =
                (List<Map<String, Object>>) versionsObj;
            for (final Map<String, Object> versionMap : versionsList) {
                if (versionMap == null) {
                    continue;
                }
                final Object versionObj = versionMap.get("version");
                if (versionObj == null) {
                    LOG.debug("Skipping entry in {} with null version", chartName);
                    continue;
                }
                final String version = versionObj.toString();
                final long createdDate = HelmScanner.parseCreated(
                    versionMap.get("created"), indexMtime
                );
                final long size = HelmScanner.resolveSize(
                    root, versionMap.get("urls")
                );
                records.add(
                    new ArtifactRecord(
                        "helm",
                        repoName,
                        chartName,
                        version,
                        size,
                        createdDate,
                        null,
                        "system",
                        null
                    )
                );
            }
        }
        return records.stream();
    }

    /**
     * Parse the {@code created} field from a version map entry.
     * Falls back to the index.yaml mtime if parsing fails.
     *
     * @param created The created field value (String, possibly ISO-8601)
     * @param fallback Fallback epoch millis (index.yaml mtime)
     * @return Epoch millis
     */
    private static long parseCreated(final Object created, final long fallback) {
        if (created == null) {
            return fallback;
        }
        final String text = created.toString();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli();
        } catch (final DateTimeParseException ex) {
            LOG.debug("Cannot parse created timestamp '{}': {}", text, ex.getMessage());
            return fallback;
        }
    }

    /**
     * Resolve the .tgz file size from the {@code urls} list.
     *
     * @param root Repository root directory
     * @param urlsObj The urls field (expected List of String)
     * @return File size in bytes, or 0 if not found
     */
    @SuppressWarnings("unchecked")
    private static long resolveSize(final Path root, final Object urlsObj) {
        if (!(urlsObj instanceof List)) {
            return 0L;
        }
        final List<Object> urls = (List<Object>) urlsObj;
        if (urls.isEmpty()) {
            return 0L;
        }
        final Object firstUrl = urls.get(0);
        if (firstUrl == null) {
            return 0L;
        }
        String filename = firstUrl.toString();
        if (filename.startsWith("http://") || filename.startsWith("https://")) {
            try {
                final String path = URI.create(filename).getPath();
                final int lastSlash = path.lastIndexOf('/');
                filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            } catch (final IllegalArgumentException ex) {
                LOG.debug("Cannot parse URL '{}': {}", filename, ex.getMessage());
                return 0L;
            }
        }
        final Path tgzPath = root.resolve(filename);
        if (Files.isRegularFile(tgzPath)) {
            try {
                return Files.size(tgzPath);
            } catch (final IOException ex) {
                LOG.debug("Cannot stat {}: {}", tgzPath, ex.getMessage());
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Get the last-modified time of the index.yaml file as epoch millis.
     *
     * @param indexPath Path to index.yaml
     * @return Epoch millis
     */
    private static long indexMtime(final Path indexPath) {
        try {
            return Files.readAttributes(indexPath, BasicFileAttributes.class)
                .lastModifiedTime().toMillis();
        } catch (final IOException ex) {
            LOG.debug("Cannot read mtime of {}: {}", indexPath, ex.getMessage());
            return System.currentTimeMillis();
        }
    }
}
