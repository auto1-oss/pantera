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

import com.auto1.pantera.cache.ValkeyConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Admin probe and awaited package invalidation of
 * {@link FilteredMetadataCache}.
 *
 * @since 2.2.9
 */
final class FilteredMetadataCacheProbeTest {

    @Test
    void probeFindsTheL1EnvelopeOfARepositoryAcrossVariants() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        FilteredMetadataCacheProbeTest.load(cache, "npm-proxy", "npm_proxy", "full", "openai");
        final FilteredMetadataCache.EnvelopeState here =
            cache.probe("npm_proxy", "openai").get(5, TimeUnit.SECONDS);
        final FilteredMetadataCache.EnvelopeState elsewhere =
            cache.probe("npm_group", "openai").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the repository's envelope is present in L1",
            here.l1Present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "another repository's probe does not see it",
            elsewhere.l1Present(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "no L2 without Valkey",
            here.l2Present(), new IsEqual<>(false)
        );
    }

    @Test
    void awaitedInvalidationCountsEveryRepositoryAndVariant() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        FilteredMetadataCacheProbeTest.load(cache, "npm-proxy", "npm_proxy", "full", "openai");
        FilteredMetadataCacheProbeTest.load(cache, "npm-proxy", "npm_proxy", "abbreviated", "openai");
        FilteredMetadataCacheProbeTest.load(cache, "npm-group", "npm_group", "full", "openai");
        FilteredMetadataCacheProbeTest.load(cache, "npm-proxy", "npm_proxy", "full", "not-openai");
        final int[] counts = cache.invalidatePackageAwaiting("openai").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "three openai envelopes dropped from L1",
            counts[0], new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the other package's envelope survives",
            cache.probe("npm_proxy", "not-openai").get(5, TimeUnit.SECONDS).l1Present(),
            new IsEqual<>(true)
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void probesAndSweepsL2EnvelopesWithAColdL1() throws Exception {
        try (ValkeyConnection conn = new ValkeyConnection(
            System.getenv("VALKEY_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379")),
            Duration.ofSeconds(2)
        )) {
            final String repo = "qa_ct_" + UUID.randomUUID();
            final String key = FilteredMetadataCache.cacheKey("npm-proxy", repo, "full", "qa-ct-pkg");
            conn.async().setex(key, 120L, "env".getBytes(StandardCharsets.UTF_8))
                .get(2, TimeUnit.SECONDS);
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final FilteredMetadataCache.EnvelopeState state =
                cache.probe(repo, "qa-ct-pkg").get(10, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the L2 envelope is seen with its TTL",
                state.l2Present() && state.l2TtlMs() > 0L, new IsEqual<>(true)
            );
            final int[] counts = cache.invalidatePackageAwaiting("qa-ct-pkg")
                .get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the sweep reports the L2 delete",
                counts[1] >= 1, new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "the key is gone once the future completed",
                conn.async().exists(key).get(2, TimeUnit.SECONDS), new IsEqual<>(0L)
            );
        }
    }

    /**
     * Load an envelope into the cache.
     *
     * @param cache Cache
     * @param type Repository type
     * @param repo Repository name
     * @param variant Variant
     * @param pkg Package
     * @throws Exception On failure
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static void load(
        final FilteredMetadataCache cache, final String type, final String repo,
        final String variant, final String pkg
    ) throws Exception {
        cache.getEntry(
            type, repo, variant, pkg,
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(
                    "{}".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)
                )
            )
        ).get(5, TimeUnit.SECONDS);
    }
}
