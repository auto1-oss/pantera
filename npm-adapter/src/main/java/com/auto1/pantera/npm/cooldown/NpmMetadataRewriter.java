/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * NPM metadata rewriter implementing cooldown SPI.
 * Serializes filtered NPM metadata back to JSON bytes.
 *
 * @since 1.0
 */
public final class NpmMetadataRewriter implements MetadataRewriter<JsonNode> {

    /**
     * Shared ObjectMapper for JSON serialization (thread-safe).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Content type for NPM metadata.
     */
    private static final String CONTENT_TYPE = "application/json";

    @Override
    public byte[] rewrite(final JsonNode metadata) throws MetadataRewriteException {
        try {
            return MAPPER.writeValueAsBytes(metadata);
        } catch (final JsonProcessingException ex) {
            throw new MetadataRewriteException("Failed to serialize NPM metadata to JSON", ex);
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
