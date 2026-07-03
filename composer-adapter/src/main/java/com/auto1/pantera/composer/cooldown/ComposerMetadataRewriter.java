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

import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PHP Composer metadata rewriter implementing cooldown SPI.
 * Serializes filtered Composer metadata back to JSON bytes.
 *
 * @since 2.2.0
 */
public final class ComposerMetadataRewriter implements MetadataRewriter<JsonNode> {

    /**
     * Shared ObjectMapper for JSON serialization (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Content type for Composer metadata.
     */
    private static final String CONTENT_TYPE = "application/json";

    @Override
    public byte[] rewrite(final JsonNode metadata) throws MetadataRewriteException {
        try {
            return MAPPER.writeValueAsBytes(metadata);
        } catch (final JsonProcessingException ex) {
            throw new MetadataRewriteException(
                "Failed to serialize Composer metadata to JSON", ex
            );
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
