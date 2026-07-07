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
package com.auto1.pantera.cooldown.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses package metadata from raw bytes into a structured representation.
 * Each adapter implements this for its specific metadata format (JSON, XML, HTML, text).
 *
 * <p>The type parameter {@code T} represents the parsed metadata structure:</p>
 * <ul>
 *   <li>NPM/Composer: Jackson {@code JsonNode}</li>
 *   <li>Maven: DOM {@code Document}</li>
 *   <li>PyPI: Jsoup {@code Document}</li>
 *   <li>Go: {@code List<String>}</li>
 * </ul>
 *
 * @param <T> Type of parsed metadata object
 * @since 1.0
 */
public interface MetadataParser<T> {

    /**
     * Parse raw metadata bytes into structured representation.
     *
     * @param bytes Raw metadata bytes
     * @return Parsed metadata object
     * @throws MetadataParseException If parsing fails
     */
    T parse(byte[] bytes) throws MetadataParseException;

    /**
     * Extract all version strings from parsed metadata.
     * Versions should be returned in their natural order from the metadata
     * (typically newest first for NPM/Composer, or as listed for Maven).
     *
     * @param metadata Parsed metadata object
     * @return List of all version strings
     */
    List<String> extractVersions(T metadata);

    /**
     * Get the "latest" version tag if the format supports it.
     * For NPM this is {@code dist-tags.latest}, for Maven it's {@code <latest>}.
     *
     * @param metadata Parsed metadata object
     * @return Latest version if present, empty otherwise
     */
    Optional<String> getLatestVersion(T metadata);

    /**
     * Get the content type for this metadata format.
     *
     * @return MIME content type (e.g., "application/json", "application/xml")
     */
    String contentType();

    /**
     * Extract release dates from parsed metadata.
     * Adapters that embed timestamps in their metadata (e.g. npm's {@code time} object)
     * should override this to enable release-date cache pre-warming.
     * Other adapters return an empty map (the inspector will fetch dates on demand).
     *
     * @param metadata Parsed metadata object
     * @return Map of version string to release timestamp (may be empty, never null)
     */
    default Map<String, Instant> extractReleaseDates(T metadata) {
        return Map.of();
    }

    /**
     * Whether the given version string denotes a prerelease in this
     * format's versioning semantics. The filter service excludes
     * prereleases when recomputing the {@code latest} pointer after
     * blocking versions.
     *
     * <p>The default is a keyword heuristic (alpha, beta, rc, snapshot, …)
     * suited to Maven-style versioning, where a hyphenated suffix is often
     * a classifier ({@code -jre}, {@code -android}), not a prerelease.
     * Formats with strict SemVer semantics (npm) MUST override: SemVer
     * defines ANY dash-suffixed identifier sequence as a prerelease, so a
     * keyword list silently promotes unknown qualifiers (e.g. nx's
     * {@code 23.1.0-pr.36127.e594f53} CI builds) to "stable" and they end
     * up as {@code dist-tags.latest}.
     *
     * @param version Version string
     * @return True if the version is a prerelease
     */
    default boolean prerelease(final String version) {
        return MetadataFilterService.isPrerelease(version);
    }
}
