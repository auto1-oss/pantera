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
package com.auto1.pantera.http.cache;

import java.util.Objects;

/**
 * Composite key for the unified negative cache (404 caching).
 *
 * <p>Every cached 404 is indexed by four fields:
 * <ul>
 *   <li>{@code scope} — the repository name (hosted, proxy, or group)</li>
 *   <li>{@code repoType} — the adapter type ({@code "maven"}, {@code "npm"}, etc.)</li>
 *   <li>{@code artifactName} — the canonical artifact identifier
 *       (e.g. {@code "@scope/pkg"}, {@code "org.spring:spring-core"})</li>
 *   <li>{@code artifactVersion} — the version string; empty for metadata endpoints</li>
 * </ul>
 *
 * <p>The {@link #flat()} method produces a colon-delimited string suitable for
 * use as a Caffeine key or a Redis/Valkey key suffix.
 *
 * @since 2.2.0
 */
public record NegativeCacheKey(
    String scope,
    String repoType,
    String artifactName,
    String artifactVersion
) {

    /**
     * Canonical constructor — validates that required fields are non-null.
     */
    public NegativeCacheKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(repoType, "repoType");
        Objects.requireNonNull(artifactName, "artifactName");
        if (artifactVersion == null) {
            artifactVersion = "";
        }
    }

    /**
     * Flat string representation suitable for cache keys.
     * Format: {@code scope:repoType:artifactName:artifactVersion}
     *
     * @return colon-delimited key string
     */
    public String flat() {
        return scope + ':' + repoType + ':' + artifactName + ':' + artifactVersion;
    }
}
