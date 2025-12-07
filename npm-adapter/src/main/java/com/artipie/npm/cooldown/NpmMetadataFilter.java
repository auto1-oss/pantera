/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.cooldown;

import com.artipie.cooldown.metadata.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;

/**
 * NPM metadata filter implementing cooldown SPI.
 * Removes blocked versions from NPM registry metadata.
 *
 * <p>Filters the following sections:</p>
 * <ul>
 *   <li>{@code versions} - removes blocked version objects</li>
 *   <li>{@code time} - removes timestamps for blocked versions</li>
 *   <li>{@code dist-tags} - updates if latest points to blocked version</li>
 * </ul>
 *
 * @since 1.0
 */
public final class NpmMetadataFilter implements MetadataFilter<JsonNode> {

    @Override
    public JsonNode filter(final JsonNode metadata, final Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final ObjectNode root = (ObjectNode) metadata;

        // Filter versions object
        final JsonNode versions = root.get("versions");
        if (versions != null && versions.isObject()) {
            final ObjectNode versionsObj = (ObjectNode) versions;
            for (final String blocked : blockedVersions) {
                versionsObj.remove(blocked);
            }
        }

        // Filter time object
        final JsonNode time = root.get("time");
        if (time != null && time.isObject()) {
            final ObjectNode timeObj = (ObjectNode) time;
            for (final String blocked : blockedVersions) {
                timeObj.remove(blocked);
            }
        }

        return root;
    }

    @Override
    public JsonNode updateLatest(final JsonNode metadata, final String newLatest) {
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final ObjectNode root = (ObjectNode) metadata;
        
        // Get or create dist-tags
        JsonNode distTags = root.get("dist-tags");
        if (distTags == null || !distTags.isObject()) {
            distTags = root.putObject("dist-tags");
        }
        
        // Update latest tag
        ((ObjectNode) distTags).put("latest", newLatest);
        
        return root;
    }

    /**
     * Remove a specific dist-tag if it points to a blocked version.
     *
     * @param metadata Metadata to modify
     * @param tagName Tag name to check
     * @param blockedVersions Set of blocked versions
     * @return Modified metadata
     */
    public JsonNode filterDistTag(
        final JsonNode metadata,
        final String tagName,
        final Set<String> blockedVersions
    ) {
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final ObjectNode root = (ObjectNode) metadata;
        final JsonNode distTags = root.get("dist-tags");
        if (distTags != null && distTags.isObject()) {
            final ObjectNode distTagsObj = (ObjectNode) distTags;
            final JsonNode tagValue = distTagsObj.get(tagName);
            if (tagValue != null && tagValue.isTextual()) {
                if (blockedVersions.contains(tagValue.asText())) {
                    distTagsObj.remove(tagName);
                }
            }
        }
        return root;
    }
}
