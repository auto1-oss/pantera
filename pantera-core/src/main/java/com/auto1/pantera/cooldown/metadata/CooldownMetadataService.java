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

import com.auto1.pantera.cooldown.api.CooldownInspector;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Service for filtering package metadata to remove blocked versions.
 * This is the main entry point for cooldown-based metadata filtering.
 *
 * <p>The service:</p>
 * <ol>
 *   <li>Parses raw metadata using the provided parser</li>
 *   <li>Extracts all versions from metadata</li>
 *   <li>Evaluates cooldown for each version (bounded to latest N)</li>
 *   <li>Filters out blocked versions</li>
 *   <li>Updates "latest" tag if needed</li>
 *   <li>Serializes filtered metadata</li>
 *   <li>Caches the result</li>
 * </ol>
 *
 * @since 1.0
 */
public interface CooldownMetadataService {

    /**
     * Filter metadata to remove blocked versions.
     *
     * @param repoType Repository type (e.g., "npm", "maven")
     * @param repoName Repository name
     * @param packageName Package name
     * @param rawMetadata Raw metadata bytes from upstream
     * @param parser Parser for this metadata format
     * @param filter Filter for this metadata format
     * @param rewriter Rewriter for this metadata format
     * @param inspector Optional cooldown inspector for release date lookups
     * @param <T> Type of parsed metadata
     * @return CompletableFuture with filtered metadata bytes
     * @throws AllVersionsBlockedException If all versions are blocked
     */
    <T> CompletableFuture<byte[]> filterMetadata(
        String repoType,
        String repoName,
        String packageName,
        byte[] rawMetadata,
        MetadataParser<T> parser,
        MetadataFilter<T> filter,
        MetadataRewriter<T> rewriter,
        Optional<CooldownInspector> inspector
    );

    /**
     * Invalidate cached metadata for a package.
     * Called when a version is blocked or unblocked.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param packageName Package name
     */
    void invalidate(String repoType, String repoName, String packageName);

    /**
     * Invalidate all cached metadata for a repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    void invalidateAll(String repoType, String repoName);

    /**
     * Get cache statistics.
     *
     * @return Statistics string
     */
    String stats();
}
