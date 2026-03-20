/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

import java.util.List;
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
}
