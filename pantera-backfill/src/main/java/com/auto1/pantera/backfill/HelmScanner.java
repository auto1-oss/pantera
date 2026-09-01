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
                final Object urls = versionMap.get("urls");
                final long size = HelmScanner.resolveSize(root, urls);
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
                        HelmScanner.resolveChartKey(root, urls)
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
        long result = 0L;
        final String key = HelmScanner.resolveChartKey(root, urlsObj);
        if (key != null) {
            try {
                result = Files.size(root.resolve(key));
            } catch (final IOException ex) {
                LOG.debug("Cannot stat {}: {}", key, ex.getMessage());
            }
        }
        return result;
    }

    /**
     * Resolve the chart tarball's repo-relative storage key from an index.yaml
     * {@code urls} entry.
     *
     * <p>Recorded as the record's path prefix so the tree browser can open the
     * chart's directory. Only ever returns a key that resolves to a file that
     * is actually there — a fabricated path would browse to nothing, which is
     * the very bug this guards against.</p>
     *
     * @param root Repository root
     * @param urlsObj The {@code urls} value from the index entry
     * @return Repo-relative key, or null when no file matches
     */
    private static String resolveChartKey(final Path root, final Object urlsObj) {
        String result = null;
        if (urlsObj instanceof List) {
            final List<Object> urls = (List<Object>) urlsObj;
            if (!urls.isEmpty() && urls.get(0) != null) {
                result = HelmScanner.existingKey(root, urls.get(0).toString());
            }
        }
        return result;
    }

    /**
     * Pick whichever of the URL's full path or its bare filename exists under
     * the repository root. Charts are written per-chart
     * ({@code <chart>/<chart>-<version>.tgz}) but older layouts are flat.
     *
     * @param root Repository root
     * @param url Raw url value from index.yaml
     * @return Repo-relative key of an existing file, or null
     */
    private static String existingKey(final Path root, final String url) {
        String candidate = url;
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            try {
                candidate = URI.create(candidate).getPath();
            } catch (final IllegalArgumentException ex) {
                LOG.debug("Cannot parse URL '{}': {}", candidate, ex.getMessage());
                candidate = "";
            }
        }
        final String relative = candidate.replaceFirst("^/+", "");
        String result = null;
        if (!relative.isEmpty() && Files.isRegularFile(root.resolve(relative))) {
            result = relative;
        } else {
            final int slash = relative.lastIndexOf('/');
            final String base;
            if (slash >= 0) {
                base = relative.substring(slash + 1);
            } else {
                base = relative;
            }
            if (!base.isEmpty() && Files.isRegularFile(root.resolve(base))) {
                result = base;
            }
        }
        return result;
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
