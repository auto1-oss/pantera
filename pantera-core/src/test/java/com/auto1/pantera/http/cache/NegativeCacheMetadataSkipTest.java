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

import com.auto1.pantera.cache.NegativeCacheConfig;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the negative-cache correctness fixes:
 * <ul>
 *   <li>Fix 1: version-less (metadata / packument) 404s are never hard-cached —
 *       a package's dynamic listing can gain a version at any moment, and a
 *       laundered upstream 4xx can masquerade as a 404, so caching it produced
 *       long-lived false 404s (the {@code npm_group/<pkg>} regression).</li>
 *   <li>Fix 3: {@link NegativeCache#invalidateByArtifactNames} clears every
 *       entry matching any name in ONE L1 scan (the async DB consumer commits
 *       many names per batch; per-name scans over a 200k L1 would blow the
 *       flush cadence).</li>
 * </ul>
 *
 * @since 2.2.2
 */
final class NegativeCacheMetadataSkipTest {

    /**
     * @return an enabled, L1-only cache (no Valkey in unit tests).
     */
    private static NegativeCache l1Cache() {
        return new NegativeCache(new NegativeCacheConfig());
    }

    @Test
    void versionlessMetadataRequestIsNotNegativeCached() {
        final NegativeCache cache = l1Cache();
        final NegativeCacheKey packument =
            new NegativeCacheKey("npm_group", "npm-group", "lodash", "");
        cache.cacheNotFound(packument);
        MatcherAssert.assertThat(
            cache.isKnown404(packument), new IsEqual<>(false)
        );
    }

    @Test
    void versionedArtifactIsStillNegativeCached() {
        final NegativeCache cache = l1Cache();
        final NegativeCacheKey versioned =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "lodash", "9.9.9");
        cache.cacheNotFound(versioned);
        MatcherAssert.assertThat(
            cache.isKnown404(versioned), new IsEqual<>(true)
        );
    }

    @Test
    void invalidateByArtifactNamesClearsAllScopesInOnePass() {
        final NegativeCache cache = l1Cache();
        final NegativeCacheKey groupScoped =
            new NegativeCacheKey("npm_group", "npm-group", "lodash", "1.0.0");
        final NegativeCacheKey proxyScoped =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "lodash", "2.0.0");
        final NegativeCacheKey unrelated =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "left-pad", "1.0.0");
        cache.cacheNotFound(groupScoped);
        cache.cacheNotFound(proxyScoped);
        cache.cacheNotFound(unrelated);

        final int cleared = cache.invalidateByArtifactNames(List.of("lodash"));

        MatcherAssert.assertThat(
            "both lodash entries (group + proxy scope) cleared in one scan",
            cleared, new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "group-scoped lodash entry is gone",
            cache.isKnown404(groupScoped), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "proxy-scoped lodash entry is gone",
            cache.isKnown404(proxyScoped), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an unrelated artifact is untouched",
            cache.isKnown404(unrelated), new IsEqual<>(true)
        );
    }

    @Test
    void invalidateByArtifactNamesMatchesMultipleNames() {
        final NegativeCache cache = l1Cache();
        final NegativeCacheKey one =
            new NegativeCacheKey("p", "npm-proxy", "lodash", "1.0.0");
        final NegativeCacheKey two =
            new NegativeCacheKey("p", "npm-proxy", "react", "1.0.0");
        final NegativeCacheKey three =
            new NegativeCacheKey("p", "npm-proxy", "vue", "1.0.0");
        cache.cacheNotFound(one);
        cache.cacheNotFound(two);
        cache.cacheNotFound(three);

        final int cleared = cache.invalidateByArtifactNames(List.of("lodash", "react"));

        MatcherAssert.assertThat(
            "only the two named artifacts are cleared",
            cleared, new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "unnamed artifact survives",
            cache.isKnown404(three), new IsEqual<>(true)
        );
    }
}
