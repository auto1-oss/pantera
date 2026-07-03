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
 * One implementation per ecosystem. Each source knows how to query its
 * canonical registry (Maven Central Solr, npm registry, PyPI JSON, etc.)
 * for the immutable publish date of a given (name, version).
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Be safe for concurrent use</li>
 *   <li>Apply a per-call timeout (≤ 5s recommended)</li>
 *   <li>Return {@code Optional.empty()} for "not found in registry" — never throw</li>
 *   <li>Complete the future exceptionally only on transient errors (network,
 *       5xx) so callers can distinguish "definitively absent" from "try again"</li>
 * </ul>
 */
public interface PublishDateSource {

    /** The {@code repo_type} this source serves (e.g. "maven", "npm"). */
    String repoType();

    /** Free-form id stored in {@code artifact_publish_dates.source}. */
    String sourceId();

    /**
     * Look up canonical publish date for {@code (name, version)}.
     * @param name Artifact name (per Pantera's repo-type-specific naming convention)
     * @param version Artifact version
     * @return future of {@code Optional.of(instant)} if registry has it,
     *         {@code Optional.empty()} if registry confirms absence,
     *         exceptionally completed on transient errors.
     */
    CompletableFuture<Optional<Instant>> fetch(String name, String version);
}
