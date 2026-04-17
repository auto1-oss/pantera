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
import java.util.ArrayList;
import java.util.Collections;
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
        if (pkgNode == null || !pkgNode.isObject()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        final Iterator<String> fields = pkgNode.fieldNames();
        while (fields.hasNext()) {
            result.add(fields.next());
        }
        return result;
    }

    @Override
    public Optional<String> getLatestVersion(final JsonNode metadata) {
        // Composer packages.json does not have a "latest" tag;
        // the client resolves constraints from the full version map.
        return Optional.empty();
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
     * Find the package version-map node inside the metadata.
     * Navigates {@code packages.{first-key}} to reach the object whose
     * field names are version strings.
     *
     * @param metadata Root metadata node
     * @return Package version map node, or {@code null} if not found
     */
    private JsonNode findPackageNode(final JsonNode metadata) {
        final JsonNode packages = metadata.get("packages");
        if (packages == null || !packages.isObject() || packages.size() == 0) {
            return null;
        }
        final String name = packages.fieldNames().next();
        final JsonNode pkgNode = packages.get(name);
        if (pkgNode != null && pkgNode.isObject()) {
            return pkgNode;
        }
        return null;
    }
}
