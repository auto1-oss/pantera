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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.pypi.cooldown.Pep440VersionComparator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Renders PEP 691 (v1.1) JSON Simple Repository API responses.
 * Includes upload-time and per-file {@code size} (PEP 700) and the
 * top-level {@code versions} array (PEP 700).
 */
public final class SimpleJsonRenderer {

    private SimpleJsonRenderer() {
    }

    /**
     * Render a package detail page as PEP 691 JSON.
     * @param packageName Normalized package name
     * @param files List of file entries
     * @return JSON string
     */
    public static String render(final String packageName, final List<FileEntry> files) {
        final JsonArrayBuilder filesArray = Json.createArrayBuilder();
        for (final FileEntry file : files) {
            filesArray.add(renderFile(file));
        }
        return Json.createObjectBuilder()
            .add("meta", Json.createObjectBuilder().add("api-version", "1.1"))
            .add("name", packageName)
            .add("versions", renderVersions(files))
            .add("files", filesArray)
            .build()
            .toString();
    }

    /**
     * Render a single file entry as a PEP 691/700/714 JSON object.
     */
    private static JsonObjectBuilder renderFile(final FileEntry file) {
        final JsonObjectBuilder entry = Json.createObjectBuilder()
            .add("filename", file.filename())
            .add("url", file.url() + "#sha256=" + file.sha256())
            .add("hashes", Json.createObjectBuilder().add("sha256", file.sha256()))
            .add("size", file.size());
        if (file.requiresPython() != null && !file.requiresPython().isEmpty()) {
            entry.add("requires-python", file.requiresPython());
        }
        if (file.uploadTime() != null) {
            // PEP 700: upload-time format is yyyy-mm-ddThh:mm:ss.ffffffZ
            // with max 6 fractional digits. Truncate to microseconds to
            // avoid emitting the 9-digit nanosecond form produced by
            // Instant.toString() when the source Instant has nano
            // precision (Linux filesystem creationTime). Python's
            // datetime.fromisoformat rejects >6 fractional digits on
            // all versions through 3.13, which breaks pip parsing.
            entry.add(
                "upload-time",
                file.uploadTime().truncatedTo(ChronoUnit.MICROS).toString()
            );
        }
        // PEP 691: yanked is either boolean false (not yanked) or
        // a string (yanked reason, may be empty). A boolean true
        // is non-compliant — pip expects a string when yanked.
        if (file.yanked()) {
            entry.add("yanked", file.yankedReason().orElse(""));
        } else {
            entry.add("yanked", false);
        }
        if (file.distInfoMetadata().isPresent()) {
            // PEP 714 renamed the JSON key to "core-metadata" — the
            // previous "data-dist-info-metadata" was the HTML *attribute*
            // name, not a valid PEP 691/714 JSON key, so compliant
            // clients silently ignored it. "dist-info-metadata" is kept
            // as a legacy-client compat alias with the identical value.
            final String sha256 = file.distInfoMetadata().get();
            entry.add("core-metadata", Json.createObjectBuilder().add("sha256", sha256));
            entry.add("dist-info-metadata", Json.createObjectBuilder().add("sha256", sha256));
        }
        return entry;
    }

    /**
     * PEP 700 top-level {@code versions} array: the distinct set of
     * versions present across all files, sorted per PEP 440 ordering.
     * Files whose version could not be determined (legacy layouts with
     * no version folder) are excluded rather than surfaced as {@code
     * null}.
     */
    private static JsonArrayBuilder renderVersions(final List<FileEntry> files) {
        final Set<String> distinct = new LinkedHashSet<>();
        for (final FileEntry file : files) {
            if (file.version() != null && !file.version().isEmpty()) {
                distinct.add(file.version());
            }
        }
        final List<String> sorted = distinct.stream()
            .sorted(new Pep440VersionComparator())
            .toList();
        final JsonArrayBuilder versions = Json.createArrayBuilder();
        for (final String version : sorted) {
            versions.add(version);
        }
        return versions;
    }

    /**
     * A file entry for the PEP 691 JSON response.
     * @param filename Distribution filename
     * @param url Relative or absolute download URL (sha256 fragment appended at render time)
     * @param sha256 Hex SHA-256 digest of the file content
     * @param requiresPython PEP 345 Requires-Python constraint, or empty/null
     * @param uploadTime Upload timestamp, or null when unknown
     * @param yanked Whether the file has been yanked (PEP 592)
     * @param yankedReason Optional yank reason
     * @param distInfoMetadata Optional PEP 658/714 core-metadata sha256
     * @param size File size in bytes (PEP 700)
     * @param version Extracted distribution version, or null when it could not be
     *                determined from the storage layout — excluded from the
     *                top-level {@code versions} array in that case
     */
    public record FileEntry(
        String filename,
        String url,
        String sha256,
        String requiresPython,
        Instant uploadTime,
        boolean yanked,
        Optional<String> yankedReason,
        Optional<String> distInfoMetadata,
        long size,
        String version
    ) {}
}
