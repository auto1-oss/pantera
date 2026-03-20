/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

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
