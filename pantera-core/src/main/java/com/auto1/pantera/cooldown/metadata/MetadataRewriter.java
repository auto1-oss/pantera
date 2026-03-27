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
package com.auto1.pantera.cooldown.metadata;

/**
 * Serializes filtered metadata back to bytes for HTTP response.
 * Each adapter implements this to serialize its metadata format.
 *
 * @param <T> Type of parsed metadata object (must match {@link MetadataParser} and {@link MetadataFilter})
 * @since 1.0
 */
public interface MetadataRewriter<T> {

    /**
     * Serialize filtered metadata to bytes.
     *
     * @param metadata Filtered metadata object
     * @return Serialized bytes ready for HTTP response
     * @throws MetadataRewriteException If serialization fails
     */
    byte[] rewrite(T metadata) throws MetadataRewriteException;

    /**
     * Get the HTTP Content-Type header value for this metadata format.
     *
     * @return Content-Type value (e.g., "application/json", "application/xml", "text/html")
     */
    String contentType();
}
