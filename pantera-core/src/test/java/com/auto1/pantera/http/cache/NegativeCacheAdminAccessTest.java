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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Admin accessors of {@link NegativeCache} in single-tier (L1-only) mode:
 * key listing without reflection, stats-neutral presence checks and honest
 * invalidation counts.
 *
 * @since 2.2.9
 */
final class NegativeCacheAdminAccessTest {

    @Test
    void listsL1KeysInFlatForm() {
        final NegativeCache cache = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey key =
            new NegativeCacheKey("maven_proxy", "maven-proxy", "com/example/foo", "1.0");
        cache.cacheNotFound(key);
        MatcherAssert.assertThat(cache.l1Keys(), new IsEqual<>(Set.of(key.flat())));
    }

    @Test
    void presenceCheckDoesNotCountAsTraffic() {
        final NegativeCache cache = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey key =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "lodash", "1.0.0");
        cache.cacheNotFound(key);
        cache.inL1(key);
        cache.inL1(new NegativeCacheKey("npm_proxy", "npm-proxy", "other", "1.0.0"));
        MatcherAssert.assertThat(
            "admin presence checks must not move the serving hit/miss counters",
            cache.stats().requestCount(), new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "the cached key is reported present",
            cache.inL1(key), new IsEqual<>(true)
        );
    }

    @Test
    void singleInvalidationReportsWhatWasActuallyRemoved() throws Exception {
        final NegativeCache cache = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey key =
            new NegativeCacheKey("npm_group", "npm-group", "lodash", "1.0.0/lodash-1.0.0.tgz");
        cache.cacheNotFound(key);
        final NegativeCache.Invalidation first =
            cache.invalidateCounted(key).get(5, TimeUnit.SECONDS);
        final NegativeCache.Invalidation second =
            cache.invalidateCounted(key).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "first invalidation removed the L1 entry and no L2 exists",
            first, new IsEqual<>(new NegativeCache.Invalidation(1, 0))
        );
        MatcherAssert.assertThat(
            "second invalidation found nothing to remove",
            second, new IsEqual<>(new NegativeCache.Invalidation(0, 0))
        );
    }

    @Test
    void matchingInvalidationDropsOnlyMatchingEntries() throws Exception {
        final NegativeCache cache = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey group =
            new NegativeCacheKey("npm_group", "npm-group", "lodash", "1.0.0/lodash-1.0.0.tgz");
        final NegativeCacheKey proxy =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "lodash", "1.0.0");
        final NegativeCacheKey other =
            new NegativeCacheKey("npm_proxy", "npm-proxy", "left-pad", "1.0.0");
        cache.cacheNotFound(group);
        cache.cacheNotFound(proxy);
        cache.cacheNotFound(other);
        final NegativeCache.Invalidation counts = cache.invalidateMatching(
            key -> "lodash".equals(key.artifactName())
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "both lodash entries were dropped",
            counts, new IsEqual<>(new NegativeCache.Invalidation(2, 0))
        );
        MatcherAssert.assertThat(
            "the unrelated entry survives",
            cache.l1Keys(), new IsEqual<>(Set.of(other.flat()))
        );
    }

    @Test
    void singleTierHasNoL2View() throws Exception {
        final NegativeCache cache = new NegativeCache(new NegativeCacheConfig());
        cache.cacheNotFound(new NegativeCacheKey("s", "npm-proxy", "a", "1"));
        MatcherAssert.assertThat(
            "no L2 tier without Valkey",
            cache.hasL2(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the L2 listing is empty rather than failing",
            cache.l2Keys(10).get(5, TimeUnit.SECONDS).flats().isEmpty(), new IsEqual<>(true)
        );
    }
}
