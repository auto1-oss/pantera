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
package com.auto1.pantera.cooldown.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cooldown evaluation and management service.
 */
public interface CooldownService {

    /**
     * Evaluate request and either allow it or produce cooldown information.
     *
     * @param request Request details
     * @param inspector Repository-specific inspector to fetch data such as release date
     * @return Evaluation result
     */
    CompletableFuture<CooldownResult> evaluate(CooldownRequest request, CooldownInspector inspector);

    /**
     * Manually unblock specific artifact version.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifact Artifact identifier
     * @param version Artifact version
     * @param actor Username who performed unblock
     * @return Completion future
     */
    CompletableFuture<Void> unblock(
        String repoType,
        String repoName,
        String artifact,
        String version,
        String actor
    );

    /**
     * Manually unblock all artifacts for repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param actor Username who performed unblock
     * @return Completion future
     */
    CompletableFuture<Void> unblockAll(String repoType, String repoName, String actor);

    /**
     * List currently active blocks for repository.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Future with active blocks list
     */
    CompletableFuture<List<CooldownBlock>> activeBlocks(String repoType, String repoName);

    /**
     * Mark a package as "all versions blocked".
     * Called when all versions of a package are blocked during metadata filtering.
     * Persists to database and updates metrics.
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifact Artifact/package name
     */
    default void markAllBlocked(String repoType, String repoName, String artifact) {
        // Default no-op for NoopCooldownService
    }
}
