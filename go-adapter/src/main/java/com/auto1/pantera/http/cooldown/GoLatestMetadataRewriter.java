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

import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Go {@code /@latest} metadata rewriter implementing cooldown SPI.
 *
 * <p>Serialises a {@link GoLatestInfo} back to the JSON shape the Go
 * client expects:</p>
 * <pre>
 * { "Version": "v1.2.3", "Time": "2024-05-12T00:00:00Z", "Origin": { ... } }
 * </pre>
 *
 * <p>{@code Time} and {@code Origin} are emitted only when present; the Go
 * client treats them as optional.</p>
 *
 * @since 2.2.0
 */
public final class GoLatestMetadataRewriter implements MetadataRewriter<GoLatestInfo> {

    /**
     * Content type for Go {@code @latest} JSON responses.
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Shared Jackson mapper (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] rewrite(final GoLatestInfo metadata) {
        if (metadata == null) {
            return new byte[0];
        }
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("Version", metadata.version());
        if (metadata.time() != null && !metadata.time().isEmpty()) {
            root.put("Time", metadata.time());
        }
        if (metadata.origin() != null && !metadata.origin().isNull()) {
            root.set("Origin", metadata.origin());
        }
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (final JsonProcessingException ex) {
            // Extremely unlikely for an in-memory ObjectNode. Fall back to a
            // minimal but valid JSON payload so the client still gets a
            // parseable response rather than HTTP 500.
            final String fallback = "{\"Version\":\"" + metadata.version() + "\"}";
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
