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

import com.auto1.pantera.cooldown.metadata.MetadataFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Go version-list metadata filter implementing cooldown SPI.
 * Removes blocked version lines from the parsed version list.
 *
 * @since 2.2.0
 */
public final class GoMetadataFilter implements MetadataFilter<List<String>> {

    @Override
    public List<String> filter(
        final List<String> metadata,
        final Set<String> blockedVersions
    ) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        final List<String> result = new ArrayList<>(metadata.size());
        for (final String version : metadata) {
            if (!blockedVersions.contains(version)) {
                result.add(version);
            }
        }
        return result;
    }

    @Override
    public List<String> updateLatest(
        final List<String> metadata,
        final String newLatest
    ) {
        // Go /@v/list is an unordered set of versions with no "latest" tag.
        // Nothing to update; return as-is.
        return metadata;
    }
}
