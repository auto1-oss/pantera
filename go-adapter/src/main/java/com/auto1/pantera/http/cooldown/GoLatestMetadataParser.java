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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Go {@code /@latest} metadata parser implementing cooldown SPI.
 *
 * <p>Parses the JSON-formatted {@code /@latest} endpoint response per the
 * Go module proxy spec:</p>
 * <pre>
 * { "Version": "v1.2.3", "Time": "2024-05-12T00:00:00Z", "Origin": { ... } }
 * </pre>
 *
 * <p>{@code Time} and {@code Origin} are optional per the spec.</p>
 *
 * @since 2.2.0
 */
public final class GoLatestMetadataParser implements MetadataParser<GoLatestInfo> {

    /**
     * Content type for Go {@code @latest} JSON responses.
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Shared Jackson mapper (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public GoLatestInfo parse(final byte[] bytes) throws MetadataParseException {
        if (bytes == null || bytes.length == 0) {
            throw new MetadataParseException("Empty @latest body");
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (final IOException ex) {
            throw new MetadataParseException("Malformed @latest JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new MetadataParseException("@latest body is not a JSON object");
        }
        final JsonNode versionNode = root.get("Version");
        if (versionNode == null || !versionNode.isTextual() || versionNode.asText().isEmpty()) {
            throw new MetadataParseException("@latest body missing required 'Version' field");
        }
        final JsonNode timeNode = root.get("Time");
        final String time = timeNode != null && timeNode.isTextual() ? timeNode.asText() : null;
        final JsonNode originNode = root.get("Origin");
        return new GoLatestInfo(versionNode.asText(), time, originNode);
    }

    @Override
    public List<String> extractVersions(final GoLatestInfo metadata) {
        return metadata == null ? Collections.emptyList() : List.of(metadata.version());
    }

    @Override
    public Optional<String> getLatestVersion(final GoLatestInfo metadata) {
        return metadata == null ? Optional.empty() : Optional.of(metadata.version());
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
