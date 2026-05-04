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
package com.auto1.pantera.prefetch;

import io.vertx.core.MultiMap;
import java.time.Instant;

/**
 * A unit of work for the prefetch dispatcher: fetch a single transitive
 * coordinate from an upstream and warm the cache.
 *
 * @param repoName       Originating proxy repo name.
 * @param repoType       Repo type (e.g. {@code "maven"}, {@code "npm"}).
 * @param upstreamUrl    Base URL of the upstream the originating request hit.
 * @param coord          Coordinate to fetch.
 * @param requestHeaders Headers to forward (auth, user-agent, etc).
 * @param submittedAt    When this task was created (for queue-time metrics).
 * @since 2.2.0
 */
public record PrefetchTask(
    String repoName,
    String repoType,
    String upstreamUrl,
    Coordinate coord,
    MultiMap requestHeaders,
    Instant submittedAt
) {
}
