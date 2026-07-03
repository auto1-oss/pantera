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
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Docker metadata parser implementing cooldown SPI.
 * Parses Docker registry tags/list JSON metadata and extracts tag information.
 *
 * <p>Docker tags/list structure:</p>
 * <pre>
 * {
 *   "name": "library/nginx",
 *   "tags": ["1.24", "1.25", "1.26", "latest"]
 * }
 * </pre>
 *
 * @since 2.2.0
 */
public final class DockerMetadataParser implements MetadataParser<JsonNode> {

    /**
     * Shared ObjectMapper for JSON parsing (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Content type for Docker tags/list metadata.
     */
    private static final String CONTENT_TYPE = "application/json";

    @Override
    public JsonNode parse(final byte[] bytes) throws MetadataParseException {
        if (bytes == null || bytes.length == 0) {
            throw new MetadataParseException("Empty or null input for Docker tags/list JSON");
        }
        try {
            final JsonNode node = MAPPER.readTree(bytes);
            if (node == null) {
                throw new MetadataParseException("Parsed Docker tags/list JSON was null");
            }
            return node;
        } catch (final IOException ex) {
            throw new MetadataParseException("Failed to parse Docker tags/list JSON", ex);
        }
    }

    @Override
    public List<String> extractVersions(final JsonNode metadata) {
        final JsonNode tags = metadata.get("tags");
        if (tags == null || !tags.isArray()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>(tags.size());
        for (final JsonNode tag : tags) {
            if (tag.isTextual()) {
                result.add(tag.asText());
            }
        }
        return result;
    }

    @Override
    public Optional<String> getLatestVersion(final JsonNode metadata) {
        final JsonNode tags = metadata.get("tags");
        if (tags == null || !tags.isArray()) {
            return Optional.empty();
        }
        for (final JsonNode tag : tags) {
            if (tag.isTextual() && "latest".equals(tag.asText())) {
                return Optional.of("latest");
            }
        }
        return Optional.empty();
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Get the image repository name from metadata.
     *
     * @param metadata Parsed metadata
     * @return Repository name if present, empty otherwise
     */
    public Optional<String> getRepositoryName(final JsonNode metadata) {
        final JsonNode name = metadata.get("name");
        if (name != null && name.isTextual()) {
            return Optional.of(name.asText());
        }
        return Optional.empty();
    }
}
