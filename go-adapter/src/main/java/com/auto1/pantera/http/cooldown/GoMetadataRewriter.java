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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Go version-list metadata rewriter implementing cooldown SPI.
 * Serializes the filtered version list back to newline-separated plain text.
 *
 * @since 2.2.0
 */
public final class GoMetadataRewriter implements MetadataRewriter<List<String>> {

    /**
     * Content type for Go version list responses.
     */
    private static final String CONTENT_TYPE = "text/plain";

    @Override
    public byte[] rewrite(final List<String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new byte[0];
        }
        return String.join("\n", metadata).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
