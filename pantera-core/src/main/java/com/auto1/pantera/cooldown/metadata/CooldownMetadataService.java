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

import com.auto1.pantera.audit.AuditContext;

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
     * <p>Prefer {@link #filterMetadata(String, String, String, byte[],
     * MetadataParser, MetadataFilter, MetadataRewriter, AuditContext, String)}
     * at any HTTP-entry call site — this overload has no requester identity
     * to attribute the resulting {@code artifact.audit} resolution record
     * to, so the default implementation falls back to {@link AuditContext#NONE}
     * and a generic owner label.
     *
     * @param repoType Repository type (e.g., "npm", "maven")
     * @param repoName Repository name
     * @param packageName Package name
     * @param rawMetadata Raw metadata bytes from upstream
     * @param parser Parser for this metadata format
     * @param filter Filter for this metadata format
     * @param rewriter Rewriter for this metadata format
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
        MetadataRewriter<T> rewriter
    );

    /**
     * Filter metadata to remove blocked versions, attributing the resulting
     * {@code artifact.audit} resolution record to the given requester.
     *
     * <p>Default implementation delegates to the context-less overload and
     * then emits an {@code artifact_resolution} audit record with an empty
     * filtered-versions list on success. The taxonomy contract is that EVERY
     * metadata listing view is audited — including deployments wired with
     * {@link NoopCooldownMetadataService} (no cooldown infrastructure at
     * all), where "no filtering happened" is exactly what the record should
     * say. Implementations with real filtering (e.g. {@link
     * MetadataFilterService}) override this method and emit their own,
     * more detailed record instead.
     *
     * @param repoType Repository type (e.g., "npm", "maven")
     * @param repoName Repository name
     * @param packageName Package name
     * @param rawMetadata Raw metadata bytes from upstream
     * @param parser Parser for this metadata format
     * @param filter Filter for this metadata format
     * @param rewriter Rewriter for this metadata format
     * @param ctx Request correlation context (trace id / client IP)
     * @param owner Requesting user name
     * @param <T> Type of parsed metadata
     * @return CompletableFuture with filtered metadata bytes
     * @throws AllVersionsBlockedException If all versions are blocked
     */
    default <T> CompletableFuture<byte[]> filterMetadata(
        final String repoType,
        final String repoName,
        final String packageName,
        final byte[] rawMetadata,
        final MetadataParser<T> parser,
        final MetadataFilter<T> filter,
        final MetadataRewriter<T> rewriter,
        final AuditContext ctx,
        final String owner
    ) {
        return this.filterMetadata(
            repoType, repoName, packageName, rawMetadata, parser, filter, rewriter
        ).whenComplete((bytes, error) -> {
            if (error == null) {
                com.auto1.pantera.audit.AuditLogger.resolution(
                    ctx, repoType, repoName, packageName, owner, java.util.List.of()
                );
            }
        });
    }

    /**
     * Variant-aware filtering: like {@link #filterMetadata(String, String,
     * String, byte[], MetadataParser, MetadataFilter, MetadataRewriter,
     * AuditContext, String)} but with a body-shape discriminator so two
     * shapes of the same package's metadata (npm full vs abbreviated
     * packument) cache separate envelopes. Without it, whichever shape was
     * filtered first was served to BOTH kinds of request for the envelope's
     * whole TTL. The default implementation ignores the variant and
     * delegates — correct for {@link NoopCooldownMetadataService} (nothing
     * is cached) and for single-shape formats; {@code MetadataFilterService}
     * overrides it to key the envelope cache by variant.
     *
     * @param repoType Repository type (e.g., "npm", "maven")
     * @param repoName Repository name
     * @param variant Body-shape discriminator (e.g. {@code "full"},
     *  {@code "abbreviated"})
     * @param packageName Package name
     * @param rawMetadata Raw metadata bytes from upstream
     * @param parser Parser for this metadata format
     * @param filter Filter for this metadata format
     * @param rewriter Rewriter for this metadata format
     * @param ctx Request correlation context (trace id / client IP)
     * @param owner Requesting user name
     * @param <T> Type of parsed metadata
     * @return CompletableFuture with filtered metadata bytes
     * @throws AllVersionsBlockedException If all versions are blocked
     */
    default <T> CompletableFuture<byte[]> filterMetadata(
        final String repoType,
        final String repoName,
        final String variant,
        final String packageName,
        final byte[] rawMetadata,
        final MetadataParser<T> parser,
        final MetadataFilter<T> filter,
        final MetadataRewriter<T> rewriter,
        final AuditContext ctx,
        final String owner
    ) {
        return this.filterMetadata(
            repoType, repoName, packageName, rawMetadata, parser, filter,
            rewriter, ctx, owner
        );
    }

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
     * Clear all cached metadata across all repositories.
     * Called on global policy changes (e.g. cooldown duration change)
     * that may affect all cached entries.
     */
    void clearAll();

    /**
     * Get cache statistics.
     *
     * @return Statistics string
     */
    String stats();
}
