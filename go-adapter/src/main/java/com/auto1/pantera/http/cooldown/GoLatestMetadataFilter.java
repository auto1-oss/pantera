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

import java.util.Set;

/**
 * Go {@code /@latest} metadata filter implementing cooldown SPI.
 *
 * <p>{@code /@latest} carries exactly one version. The filter semantics are:
 * <ul>
 *   <li>If the {@code Version} in {@link GoLatestInfo} is <em>not</em> in
 *       {@code blockedVersions}, return the info unchanged — the upstream
 *       latest is fine to serve.</li>
 *   <li>If the {@code Version} IS blocked, return {@code null} to signal
 *       the caller must resolve a fallback. This filter intentionally does
 *       NOT fetch {@code /@v/list} — the {@link MetadataFilter} SPI is pure
 *       (no I/O). Orchestration lives in {@code GoLatestHandler}, which
 *       fetches the sibling list, picks the highest non-blocked version,
 *       and calls {@link #updateLatest(GoLatestInfo, String)} to build the
 *       rewritten payload.</li>
 * </ul>
 *
 * <p>See {@code GoLatestHandler} for the multi-endpoint orchestration.</p>
 *
 * @since 2.2.0
 */
public final class GoLatestMetadataFilter implements MetadataFilter<GoLatestInfo> {

    @Override
    public GoLatestInfo filter(final GoLatestInfo metadata, final Set<String> blockedVersions) {
        if (metadata == null) {
            return null;
        }
        if (blockedVersions == null || blockedVersions.isEmpty()) {
            return metadata;
        }
        if (blockedVersions.contains(metadata.version())) {
            // Signal "needs fallback" — orchestration layer must resolve.
            return null;
        }
        return metadata;
    }

    @Override
    public GoLatestInfo updateLatest(final GoLatestInfo metadata, final String newLatest) {
        if (metadata == null || newLatest == null || newLatest.isEmpty()) {
            return metadata;
        }
        if (newLatest.equals(metadata.version())) {
            return metadata;
        }
        return metadata.withVersion(newLatest);
    }
}
