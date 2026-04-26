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
package com.auto1.pantera.publishdate;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves canonical publish dates with the contract:
 * <ol>
 *   <li>L1 (in-process Caffeine, no TTL — immutable) hit → return immediately</li>
 *   <li>L2 (DB {@code artifact_publish_dates} row) hit → populate L1 → return</li>
 *   <li>Source fetch (registry HTTP call) → write to DB and L1 → return</li>
 *   <li>Source returns empty or fails → return {@code Optional.empty()}; do
 *       NOT cache absence (registry might be transiently unreachable, and
 *       publish dates added later are valid retroactively)</li>
 * </ol>
 *
 * <p>Implementations are thread-safe and meant to be a long-lived singleton.
 */
public interface PublishDateRegistry {

    /**
     * Resolve publish date for {@code (repoType, name, version)}.
     * Never throws — transient errors return {@code Optional.empty()}.
     *
     * @param repoType Repository type ("maven", "npm", "pypi", "go", "composer", "gem")
     * @param name Artifact name (per Pantera's repo-type-specific naming convention)
     * @param version Artifact version
     * @return future of optional canonical publish date
     */
    CompletableFuture<Optional<Instant>> publishDate(
        String repoType, String name, String version
    );
}
