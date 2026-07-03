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

import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Docker metadata filter implementing cooldown SPI.
 * Removes blocked tags from Docker registry tags/list metadata.
 *
 * <p>Filters the {@code tags} array by removing entries matching blocked version strings.</p>
 *
 * @since 2.2.0
 */
public final class DockerMetadataFilter implements MetadataFilter<JsonNode> {

    @Override
    public JsonNode filter(final JsonNode metadata, final Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final ObjectNode root = (ObjectNode) metadata;
        final JsonNode tags = root.get("tags");
        if (tags == null || !tags.isArray()) {
            return metadata;
        }
        final ArrayNode original = (ArrayNode) tags;
        final ArrayNode filtered = root.arrayNode();
        for (final JsonNode tag : original) {
            if (tag.isTextual() && !blockedVersions.contains(tag.asText())) {
                filtered.add(tag);
            }
        }
        root.set("tags", filtered);
        return root;
    }

    @Override
    public JsonNode updateLatest(final JsonNode metadata, final String newLatest) {
        // Docker tags/list has no "latest" pointer to update.
        // The "latest" tag is simply an element in the tags array.
        // No structural change is needed beyond the filter() call.
        return metadata;
    }
}
