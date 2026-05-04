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
package com.auto1.pantera.prefetch.parser;

import java.util.Optional;

/**
 * Resolves an npm version range to a concrete version against locally
 * cached package metadata.
 *
 * <p>Returns empty if the metadata is not cached or if no cached version
 * satisfies the range. Pre-fetch NEVER initiates an upstream metadata
 * fetch — this lookup is strictly local-cache-only so pre-fetch can stay
 * a best-effort, side-effect-free warm-up.</p>
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface NpmMetadataLookup {

    /**
     * Resolve {@code versionRange} for {@code packageName} against the
     * local metadata cache.
     *
     * @param packageName  npm package name (scoped or unscoped).
     * @param versionRange Semver range expression as it appears in
     *                     {@code package.json} (e.g. {@code "^4.17.0"},
     *                     {@code "1.3.0"}, {@code "~2.0"}).
     * @return Concrete version string if a cached entry satisfies the
     *         range, otherwise {@link Optional#empty()}.
     */
    Optional<String> resolve(String packageName, String versionRange);
}
