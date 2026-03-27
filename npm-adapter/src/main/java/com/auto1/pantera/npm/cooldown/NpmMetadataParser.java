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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.ReleaseDateProvider;
import com.auto1.pantera.http.log.EcsLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NPM metadata parser implementing cooldown SPI.
 * Parses NPM registry JSON metadata and extracts version information.
 *
 * <p>NPM metadata structure:</p>
 * <pre>
 * {
 *   "name": "package-name",
 *   "dist-tags": { "latest": "1.0.0" },
 *   "versions": {
 *     "1.0.0": { ... version metadata ... },
 *     "1.0.1": { ... version metadata ... }
 *   },
 *   "time": {
 *     "created": "2020-01-01T00:00:00.000Z",
 *     "modified": "2020-06-01T00:00:00.000Z",
 *     "1.0.0": "2020-01-01T00:00:00.000Z",
 *     "1.0.1": "2020-06-01T00:00:00.000Z"
 *   }
 * }
 * </pre>
 *
 * @since 1.0
 */
public final class NpmMetadataParser implements MetadataParser<JsonNode>, ReleaseDateProvider<JsonNode> {

    /**
     * Shared ObjectMapper for JSON parsing (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Content type for NPM metadata.
     */
    private static final String CONTENT_TYPE = "application/json";

    @Override
    public JsonNode parse(final byte[] bytes) throws MetadataParseException {
        try {
            return MAPPER.readTree(bytes);
        } catch (final IOException ex) {
            throw new MetadataParseException("Failed to parse NPM metadata JSON", ex);
        }
    }

    @Override
    public List<String> extractVersions(final JsonNode metadata) {
        final JsonNode versions = metadata.get("versions");
        if (versions == null || !versions.isObject()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        final Iterator<String> fields = versions.fieldNames();
        while (fields.hasNext()) {
            result.add(fields.next());
        }
        return result;
    }

    @Override
    public Optional<String> getLatestVersion(final JsonNode metadata) {
        final JsonNode distTags = metadata.get("dist-tags");
        if (distTags != null && distTags.has("latest")) {
            final JsonNode latest = distTags.get("latest");
            if (latest != null && latest.isTextual()) {
                return Optional.of(latest.asText());
            }
        }
        return Optional.empty();
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public Map<String, Instant> releaseDates(final JsonNode metadata) {
        final JsonNode time = metadata.get("time");
        if (time == null || !time.isObject()) {
            return Collections.emptyMap();
        }
        final Map<String, Instant> result = new HashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = time.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fields.next();
            final String key = entry.getKey();
            // Skip "created" and "modified" - we only want version timestamps
            if ("created".equals(key) || "modified".equals(key)) {
                continue;
            }
            final JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                try {
                    final Instant instant = Instant.parse(value.asText());
                    result.put(key, instant);
                } catch (final DateTimeParseException ex) {
                    EcsLogger.debug("com.auto1.pantera.npm")
                        .message("Failed to parse NPM version timestamp")
                        .error(ex)
                        .log();
                }
            }
        }
        return result;
    }

    /**
     * Get the package name from metadata.
     *
     * @param metadata Parsed metadata
     * @return Package name or empty if not found
     */
    public Optional<String> getPackageName(final JsonNode metadata) {
        final JsonNode name = metadata.get("name");
        if (name != null && name.isTextual()) {
            return Optional.of(name.asText());
        }
        return Optional.empty();
    }
}
