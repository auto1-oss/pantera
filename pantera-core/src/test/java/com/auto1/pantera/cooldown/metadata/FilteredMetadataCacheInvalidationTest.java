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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FilteredMetadataCache#invalidateByPackageName(String)}'s
 * L1 semantics and the peer fan-out publisher — the single-tier half of the
 * 2.2.7 invalidation fix (the Valkey L2 sweep is covered by the gated
 * {@code FilteredMetadataCacheL2SweepTest}).
 *
 * @since 2.2.7
 */
final class FilteredMetadataCacheInvalidationTest {

    @Test
    void dropsL1EntryAndPublishesItsKey() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final List<String> published = new CopyOnWriteArrayList<>();
        cache.setInvalidationPublisher(published::add);
        final AtomicInteger loads = new AtomicInteger();
        seed(cache, "npm-proxy", "npm_proxy", "openai", loads);
        MatcherAssert.assertThat(
            "seeding must have computed the envelope once",
            loads.get(), new IsEqual<>(1)
        );

        final int dropped = cache.invalidateByPackageName("openai");

        MatcherAssert.assertThat(
            "the L1 envelope must be counted as dropped",
            dropped, new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the dropped key must be published for peer L1 fan-out",
            published,
            new IsEqual<>(List.of("metadata:npm-proxy:npm_proxy:openai"))
        );
        seed(cache, "npm-proxy", "npm_proxy", "openai", loads);
        MatcherAssert.assertThat(
            "a get after invalidation must recompute, not serve the old envelope",
            loads.get(), new IsEqual<>(2)
        );
    }

    @Test
    void matchesThePackageSegmentExactly() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final AtomicInteger loads = new AtomicInteger();
        seed(cache, "npm-proxy", "repo", "lodash", loads);
        seed(cache, "npm-proxy", "repo", "not-lodash", loads);

        final int dropped = cache.invalidateByPackageName("lodash");

        MatcherAssert.assertThat(
            "only the exact package-name segment may match",
            dropped, new IsEqual<>(1)
        );
        seed(cache, "npm-proxy", "repo", "not-lodash", loads);
        MatcherAssert.assertThat(
            "the near-miss package's envelope must survive (2 seeds + 1 recompute would be 3)",
            loads.get(), new IsEqual<>(2)
        );
    }

    @Test
    void invalidatesAcrossRepoIdentities() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final AtomicInteger loads = new AtomicInteger();
        seed(cache, "npm-proxy", "npm_proxy", "openai", loads);
        seed(cache, "npm-group", "npm_group", "openai", loads);

        MatcherAssert.assertThat(
            "envelopes for every (repoType, repoName) holding the package must drop",
            cache.invalidateByPackageName("openai"), new IsEqual<>(2)
        );
    }

    @Test
    void nullAndEmptyAreNoOps() {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        MatcherAssert.assertThat(
            "null package must be a no-op", cache.invalidateByPackageName(null), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "empty package must be a no-op", cache.invalidateByPackageName(""), new IsEqual<>(0)
        );
    }

    /**
     * Seed (or re-read) one envelope through the public getEntry path.
     */
    private static void seed(
        final FilteredMetadataCache cache,
        final String repoType,
        final String repoName,
        final String pkg,
        final AtomicInteger loads
    ) throws Exception {
        cache.getEntry(
            repoType, repoName, pkg,
            () -> {
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(
                    new FilteredMetadataCache.CacheEntry(
                        "{}".getBytes(StandardCharsets.UTF_8),
                        Optional.empty(),
                        Duration.ofMinutes(5),
                        java.util.Set.of()
                    )
                );
            }
        ).get(5, TimeUnit.SECONDS);
    }
}
