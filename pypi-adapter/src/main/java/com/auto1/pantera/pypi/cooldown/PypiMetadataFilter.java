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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PyPI metadata filter implementing cooldown SPI.
 * Removes {@code <a>} elements from the Simple Index whose extracted version
 * is in the blocked set.
 *
 * <p>Filtering rules:</p>
 * <ul>
 *   <li>Links whose extracted version is in the blocked set are removed</li>
 *   <li>Links with no parseable version are preserved (defensive — do not break downloads)</li>
 *   <li>All other metadata (HTML structure, non-link content) is preserved</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class PypiMetadataFilter implements MetadataFilter<PypiSimpleIndex> {

    @Override
    public PypiSimpleIndex filter(
        final PypiSimpleIndex metadata,
        final Set<String> blockedVersions
    ) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        final List<PypiSimpleIndex.Link> filtered = new ArrayList<>();
        for (final PypiSimpleIndex.Link link : metadata.links()) {
            final String version = link.version();
            // Keep links with no parseable version (safety: never remove what we cannot identify)
            // and links whose version is not blocked.
            if (version == null || version.isEmpty() || !blockedVersions.contains(version)) {
                filtered.add(link);
            }
        }
        return new PypiSimpleIndex(metadata.originalHtml(), filtered);
    }

    @Override
    public PypiSimpleIndex updateLatest(
        final PypiSimpleIndex metadata,
        final String newLatest
    ) {
        // PyPI Simple Index has no "latest" tag concept.
        // The index is a flat list of distribution files — there is no explicit
        // latest pointer to update. Return metadata unchanged.
        return metadata;
    }
}
