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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Renders PEP 691 (v1.1) JSON Simple Repository API responses.
 * Includes the PEP 700 fields that api-version 1.1 makes mandatory: the
 * project-level {@code versions} array and the per-file {@code size}, plus
 * the optional {@code upload-time}.
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
        final TreeSet<String> versions = new TreeSet<>(new Pep440VersionComparator());
        for (final FileEntry file : files) {
            file.effectiveVersion().ifPresent(versions::add);
            final JsonObjectBuilder entry = Json.createObjectBuilder()
                .add("filename", file.filename())
                .add("url", file.url() + "#sha256=" + file.sha256())
                .add("hashes", Json.createObjectBuilder().add("sha256", file.sha256()));
            if (file.size() >= 0) {
                entry.add("size", file.size());
            }
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
            // PEP 691: yanked is a boolean or a NON-EMPTY string (the
            // reason). An empty string is falsy, and pip maps a falsy
            // value to "not yanked" — so a reason-less yank must be
            // boolean true, never "".
            if (file.yanked()) {
                final Optional<String> reason = file.yankedReason()
                    .filter(text -> !text.isBlank());
                if (reason.isPresent()) {
                    entry.add("yanked", reason.get());
                } else {
                    entry.add("yanked", true);
                }
            } else {
                entry.add("yanked", false);
            }
            if (file.distInfoMetadata().isPresent()) {
                entry.add("data-dist-info-metadata",
                    Json.createObjectBuilder().add("sha256", file.distInfoMetadata().get()));
            }
            filesArray.add(entry);
        }
        return Json.createObjectBuilder()
            .add("meta", Json.createObjectBuilder().add("api-version", "1.1"))
            .add("name", packageName)
            .add("versions", Json.createArrayBuilder(List.copyOf(versions)))
            .add("files", filesArray)
            .build()
            .toString();
    }

    /**
     * A file entry for the PEP 691 JSON response.
     *
     * @param filename File name
     * @param url Relative URL
     * @param sha256 Hex SHA-256 digest
     * @param requiresPython Requires-Python constraint (nullable)
     * @param uploadTime Upload time (nullable)
     * @param yanked Whether the file is yanked
     * @param yankedReason Yank reason
     * @param distInfoMetadata Core-metadata digest
     * @param size File size in bytes, negative when unknown
     * @param version Release version the file belongs to (nullable: derived
     *  from the filename)
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
    ) {

        /**
         * Entry without a known size or version (the version is then derived
         * from the filename).
         * @param filename File name
         * @param url Relative URL
         * @param sha256 Hex SHA-256 digest
         * @param requiresPython Requires-Python constraint (nullable)
         * @param uploadTime Upload time (nullable)
         * @param yanked Whether the file is yanked
         * @param yankedReason Yank reason
         * @param distInfoMetadata Core-metadata digest
         */
        public FileEntry(
            final String filename,
            final String url,
            final String sha256,
            final String requiresPython,
            final Instant uploadTime,
            final boolean yanked,
            final Optional<String> yankedReason,
            final Optional<String> distInfoMetadata
        ) {
            this(
                filename, url, sha256, requiresPython, uploadTime, yanked,
                yankedReason, distInfoMetadata, -1L, null
            );
        }

        /**
         * The release version: the explicit one, or one parsed from the
         * distribution filename ({@code name-ver-...whl}, {@code name-ver.tar.gz}).
         * @return Version, empty when it cannot be determined
         */
        Optional<String> effectiveVersion() {
            final Optional<String> result;
            if (this.version != null && !this.version.isBlank()) {
                result = Optional.of(this.version);
            } else {
                result = versionFromFilename(this.filename);
            }
            return result;
        }

        /**
         * Parse the version out of a distribution filename.
         * @param name Filename
         * @return Version, empty when the name has no recognised shape
         */
        private static Optional<String> versionFromFilename(final String name) {
            final String lower = name.toLowerCase(Locale.ROOT);
            final Optional<String> result;
            if (lower.endsWith(".whl") || lower.endsWith(".egg")) {
                final String[] parts = name.substring(0, name.length() - 4).split("-");
                result = parts.length >= 2 ? Optional.of(parts[1]) : Optional.empty();
            } else {
                final String stem = stripSdistSuffix(name, lower);
                final int dash = stem.lastIndexOf('-');
                result = dash > 0 && dash < stem.length() - 1
                    ? Optional.of(stem.substring(dash + 1)) : Optional.empty();
            }
            return result;
        }

        /**
         * Remove the source-distribution archive suffix.
         * @param name Filename
         * @param lower Lower-cased filename
         * @return Filename without its archive suffix
         */
        private static String stripSdistSuffix(final String name, final String lower) {
            String stem = name;
            for (final String suffix : List.of(".tar.gz", ".tar.bz2", ".tar.z", ".tgz", ".zip", ".tar")) {
                if (lower.endsWith(suffix)) {
                    stem = name.substring(0, name.length() - suffix.length());
                    break;
                }
            }
            return stem;
        }
    }
}
