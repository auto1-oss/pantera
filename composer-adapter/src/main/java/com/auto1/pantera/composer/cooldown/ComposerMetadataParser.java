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
package com.auto1.pantera.composer.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PHP Composer metadata parser implementing cooldown SPI.
 * Parses Composer packages.json metadata and extracts version information.
 *
 * <p>Composer metadata structure (packages endpoint {@code /packages/{vendor}/{pkg}.json}
 * or {@code /p2/{vendor}/{pkg}.json}):</p>
 * <pre>
 * {
 *   "packages": {
 *     "vendor/package": {
 *       "1.0.0": {"name": "vendor/package", "version": "1.0.0", ...},
 *       "1.1.0": {"name": "vendor/package", "version": "1.1.0", ...},
 *       "2.0.0": {"name": "vendor/package", "version": "2.0.0", ...}
 *     }
 *   }
 * }
 * </pre>
 *
 * @since 2.2.0
 */
public final class ComposerMetadataParser implements MetadataParser<JsonNode> {

    /**
     * Shared ObjectMapper for JSON parsing (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Content type for Composer metadata.
     */
    private static final String CONTENT_TYPE = "application/json";

    @Override
    public JsonNode parse(final byte[] bytes) throws MetadataParseException {
        if (bytes == null || bytes.length == 0) {
            throw new MetadataParseException("Empty or null Composer metadata");
        }
        try {
            final JsonNode node = MAPPER.readTree(bytes);
            if (node == null) {
                throw new MetadataParseException("Parsed Composer metadata is null");
            }
            return node;
        } catch (final IOException ex) {
            throw new MetadataParseException("Failed to parse Composer metadata JSON", ex);
        }
    }

    @Override
    public List<String> extractVersions(final JsonNode metadata) {
        final JsonNode pkgNode = this.findPackageNode(metadata);
        if (pkgNode == null) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        if (pkgNode.isObject()) {
            final Iterator<String> fields = pkgNode.fieldNames();
            while (fields.hasNext()) {
                result.add(fields.next());
            }
        } else if (pkgNode.isArray()) {
            for (final JsonNode entry : pkgNode) {
                final JsonNode versionNode = entry.get("version");
                if (versionNode != null && versionNode.isTextual()) {
                    result.add(versionNode.asText());
                }
            }
        }
        return result;
    }

    @Override
    public Optional<String> getLatestVersion(final JsonNode metadata) {
        // Composer packages.json does not have a "latest" tag;
        // the client resolves constraints from the full version map.
        return Optional.empty();
    }

    /**
     * Extract {@code version -> time} from the inlined Composer
     * packument. Both v1 (object whose keys are version strings) and v2
     * (array of version objects under {@code /p2/...}) ship the release
     * timestamp in each version's {@code time} field as an RFC 3339 /
     * ISO-8601 instant. Returning this map lets the cooldown filter use
     * {@code evaluateWithKnownDate(...)} and skip the per-version
     * {@code inspector.releaseDate(...)} round-trip — the same
     * packument-inline shortcut npm and PyPI already take.
     *
     * <p>Versions whose {@code time} field is missing or unparseable are
     * silently omitted from the map: cooldown then treats them as
     * release-date-unknown and allows. That matches the npm semantics
     * established in {@code dbdde1736}.</p>
     *
     * @param metadata Parsed Composer metadata
     * @return Immutable {@code version -> Instant} map (may be empty)
     */
    @Override
    public Map<String, Instant> extractReleaseDates(final JsonNode metadata) {
        final JsonNode pkgNode = this.findPackageNode(metadata);
        if (pkgNode == null) {
            return Map.of();
        }
        final Map<String, Instant> result = new HashMap<>();
        if (pkgNode.isObject()) {
            final Iterator<Map.Entry<String, JsonNode>> entries = pkgNode.fields();
            while (entries.hasNext()) {
                final Map.Entry<String, JsonNode> entry = entries.next();
                parseTime(entry.getValue())
                    .ifPresent(time -> result.put(entry.getKey(), time));
            }
        } else if (pkgNode.isArray()) {
            for (final JsonNode entry : pkgNode) {
                final JsonNode versionNode = entry.get("version");
                if (versionNode == null || !versionNode.isTextual()) {
                    continue;
                }
                parseTime(entry).ifPresent(
                    time -> result.put(versionNode.asText(), time)
                );
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Parse the {@code time} field of a Composer version object.
     *
     * @param versionObj A version object (either a v1 versions-map value
     *                   or a v2 array element)
     * @return The parsed instant, or empty when missing/unparseable
     */
    private static Optional<Instant> parseTime(final JsonNode versionObj) {
        if (versionObj == null || !versionObj.isObject()) {
            return Optional.empty();
        }
        final JsonNode timeNode = versionObj.get("time");
        if (timeNode == null || !timeNode.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(timeNode.asText()).toInstant());
        } catch (final DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Get the package name from metadata.
     * Returns the first (and typically only) key under the "packages" object.
     *
     * @param metadata Parsed metadata
     * @return Package name if present, empty otherwise
     */
    public Optional<String> getPackageName(final JsonNode metadata) {
        final JsonNode packages = metadata.get("packages");
        if (packages != null && packages.isObject() && packages.size() > 0) {
            return Optional.of(packages.fieldNames().next());
        }
        return Optional.empty();
    }

    /**
     * Find the package version-map node inside the metadata. Returns the
     * value at {@code packages.{first-key}} regardless of shape — v1
     * exposes an object whose field names are version strings, v2
     * ({@code /p2/...}) exposes a JSON array of objects each carrying a
     * {@code version} field. Callers must branch on
     * {@link JsonNode#isObject()} / {@link JsonNode#isArray()}.
     *
     * @param metadata Root metadata node
     * @return Package version-map node (object or array), or {@code null}
     *     if not present
     */
    private JsonNode findPackageNode(final JsonNode metadata) {
        final JsonNode packages = metadata.get("packages");
        if (packages == null || !packages.isObject() || packages.size() == 0) {
            return null;
        }
        final String name = packages.fieldNames().next();
        final JsonNode pkgNode = packages.get(name);
        if (pkgNode == null) {
            return null;
        }
        if (pkgNode.isObject() || pkgNode.isArray()) {
            return pkgNode;
        }
        return null;
    }
}
