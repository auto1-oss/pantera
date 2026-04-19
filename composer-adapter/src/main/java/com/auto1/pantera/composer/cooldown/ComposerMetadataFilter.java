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

import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * PHP Composer metadata filter implementing cooldown SPI.
 * Removes blocked version keys from the Composer packages.json version map.
 *
 * <p>Filters the {@code packages.{vendor/package}} object by removing blocked
 * version keys. Composer has no "latest" tag in the metadata format, so
 * {@link #updateLatest(JsonNode, String)} is a no-op that returns metadata unchanged.</p>
 *
 * @since 2.2.0
 */
public final class ComposerMetadataFilter implements MetadataFilter<JsonNode> {

    @Override
    public JsonNode filter(final JsonNode metadata, final Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final JsonNode packages = metadata.get("packages");
        if (packages == null || !packages.isObject() || packages.size() == 0) {
            return metadata;
        }
        final String name = packages.fieldNames().next();
        final JsonNode pkgNode = packages.get(name);
        if (pkgNode != null && pkgNode.isObject()) {
            final ObjectNode versionsObj = (ObjectNode) pkgNode;
            for (final String blocked : blockedVersions) {
                versionsObj.remove(blocked);
            }
        }
        return metadata;
    }

    @Override
    public JsonNode updateLatest(final JsonNode metadata, final String newLatest) {
        // Composer packages.json has no "latest" tag — the client resolves
        // version constraints from the full version map. No-op.
        return metadata;
    }
}
