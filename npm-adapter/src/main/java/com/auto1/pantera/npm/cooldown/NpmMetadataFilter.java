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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * NPM metadata filter implementing cooldown SPI.
 * Removes blocked versions from NPM registry metadata.
 *
 * <p>Filters the following sections:</p>
 * <ul>
 *   <li>{@code versions} - removes blocked version objects</li>
 *   <li>{@code time} - removes timestamps for blocked versions</li>
 *   <li>{@code dist-tags} - drops any non-{@code latest} tag entry whose target
 *       is a blocked version (so {@code npm install pkg@beta} fails cleanly
 *       instead of silently resolving to a different version). The
 *       {@code latest} tag is updated separately by
 *       {@link #updateLatest(JsonNode, String)} when the orchestrator picks
 *       a fallback version.</li>
 * </ul>
 *
 * @since 1.0
 */
public final class NpmMetadataFilter implements MetadataFilter<JsonNode> {

    /**
     * Name of the {@code latest} dist-tag. The {@code latest} tag is handled
     * separately (rewritten to the most recent non-blocked version) — it is
     * never dropped because unbounded {@code npm install pkg} depends on it.
     */
    private static final String LATEST_TAG = "latest";

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

        // Filter dist-tags: drop non-latest tags pointing to blocked versions.
        // `latest` is left alone here because MetadataFilterService calls
        // updateLatest() after this with the chosen fallback version. Dropping
        // non-latest tags (beta, next, canary, ...) is safer than rewriting
        // them — `npm install pkg@beta` should fail-closed rather than
        // silently resolve to a version the user didn't ask for.
        final JsonNode distTags = root.get("dist-tags");
        if (distTags != null && distTags.isObject()) {
            final ObjectNode distTagsObj = (ObjectNode) distTags;
            final Iterator<String> tagNames = distTagsObj.fieldNames();
            // Collect first to avoid ConcurrentModificationException
            final List<String> toRemove = new ArrayList<>();
            while (tagNames.hasNext()) {
                final String tagName = tagNames.next();
                if (LATEST_TAG.equals(tagName)) {
                    continue;
                }
                final JsonNode target = distTagsObj.get(tagName);
                if (target != null && target.isTextual()
                    && blockedVersions.contains(target.asText())) {
                    toRemove.add(tagName);
                }
            }
            for (final String tagName : toRemove) {
                distTagsObj.remove(tagName);
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
            if (tagValue != null && tagValue.isTextual()
                && blockedVersions.contains(tagValue.asText())) {
                distTagsObj.remove(tagName);
            }
        }
        return root;
    }
}
