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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Go version-list metadata parser implementing cooldown SPI.
 * Parses the plain-text {@code /@v/list} endpoint response where each
 * line is a version string (e.g. {@code v1.2.3}).
 *
 * <p>Example response:</p>
 * <pre>
 * v0.1.0
 * v0.2.0
 * v1.0.0
 * v1.1.0
 * v2.0.0-beta.1
 * v2.0.0
 * </pre>
 *
 * @since 2.2.0
 */
public final class GoMetadataParser implements MetadataParser<List<String>> {

    /**
     * Content type for Go version list responses.
     */
    private static final String CONTENT_TYPE = "text/plain";

    @Override
    public List<String> parse(final byte[] bytes) throws MetadataParseException {
        if (bytes == null || bytes.length == 0) {
            return Collections.emptyList();
        }
        final String body = new String(bytes, StandardCharsets.UTF_8);
        final String[] lines = body.split("\n", -1);
        final List<String> versions = new ArrayList<>(lines.length);
        for (final String line : lines) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                versions.add(trimmed);
            }
        }
        return versions;
    }

    @Override
    public List<String> extractVersions(final List<String> metadata) {
        return metadata == null ? Collections.emptyList() : List.copyOf(metadata);
    }

    @Override
    public Optional<String> getLatestVersion(final List<String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(metadata.get(metadata.size() - 1));
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
