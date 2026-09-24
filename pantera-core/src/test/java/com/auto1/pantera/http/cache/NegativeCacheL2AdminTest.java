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
import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Cluster-wide behaviour of the negative cache's admin operations against a
 * real Valkey: two cache instances share one L2, as two nodes do. The
 * instance doing the admin work never cached the entries itself (cold L1),
 * which is exactly the case the L1-only implementation got wrong.
 * Valkey-gated — same convention as {@code FilteredMetadataCacheL2SweepTest}.
 *
 * @since 2.2.9
 */
final class NegativeCacheL2AdminTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void listsAndProbesEntriesAnotherNodeWrote() throws Exception {
        try (ValkeyConnection conn = NegativeCacheL2AdminTest.connection()) {
            final String scope = "qa_ct_" + UUID.randomUUID();
            final NegativeCacheKey key = new NegativeCacheKey(scope, "npm-proxy", "lodash", "1.0.0");
            NegativeCacheL2AdminTest.node(conn).cacheNotFound(key);
            final NegativeCache admin = NegativeCacheL2AdminTest.node(conn);
            NegativeCacheL2AdminTest.await(
                () -> admin.l2Keys(1_000_000).get(5, TimeUnit.SECONDS)
                    .flats().contains(key.flat())
            );
            MatcherAssert.assertThat(
                "the entry is in L2 with a live TTL",
                admin.l2TtlMillis(List.of(key.flat())).get(5, TimeUnit.SECONDS)
                    .get(key.flat()) > 0L,
                new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "this node never cached it locally",
                admin.inL1(key), new IsEqual<>(false)
            );
            admin.invalidateCounted(key).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void singleInvalidationCountsTheL2Delete() throws Exception {
        try (ValkeyConnection conn = NegativeCacheL2AdminTest.connection()) {
            final String scope = "qa_ct_" + UUID.randomUUID();
            final NegativeCacheKey key = new NegativeCacheKey(scope, "npm-proxy", "axios", "2.0.0");
            NegativeCacheL2AdminTest.node(conn).cacheNotFound(key);
            final NegativeCache admin = NegativeCacheL2AdminTest.node(conn);
            NegativeCacheL2AdminTest.await(
                () -> admin.l2TtlMillis(List.of(key.flat())).get(5, TimeUnit.SECONDS)
                    .get(key.flat()) > 0L
            );
            MatcherAssert.assertThat(
                admin.invalidateCounted(key).get(5, TimeUnit.SECONDS),
                new IsEqual<>(new NegativeCache.Invalidation(0, 1))
            );
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void matchingInvalidationSweepsL2WithAColdL1() throws Exception {
        try (ValkeyConnection conn = NegativeCacheL2AdminTest.connection()) {
            final String scope = "qa_ct_" + UUID.randomUUID();
            final NegativeCache writer = NegativeCacheL2AdminTest.node(conn);
            final NegativeCacheKey first = new NegativeCacheKey(scope, "npm-group", "openai", "4.0.0/openai-4.0.0.tgz");
            final NegativeCacheKey second = new NegativeCacheKey(scope, "npm-proxy", "openai", "4.0.0");
            final NegativeCacheKey other = new NegativeCacheKey(scope, "npm-proxy", "other", "1.0.0");
            writer.cacheNotFound(first);
            writer.cacheNotFound(second);
            writer.cacheNotFound(other);
            final NegativeCache admin = NegativeCacheL2AdminTest.node(conn);
            NegativeCacheL2AdminTest.await(
                () -> admin.l2TtlMillis(List.of(first.flat(), second.flat(), other.flat()))
                    .get(5, TimeUnit.SECONDS).values().stream().allMatch(ttl -> ttl > 0L)
            );
            final NegativeCache.Invalidation counts = admin.invalidateMatching(
                key -> scope.equals(key.scope()) && "openai".equals(key.artifactName())
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "both openai entries were deleted from L2 although this node's L1 was cold",
                counts, new IsEqual<>(new NegativeCache.Invalidation(0, 2))
            );
            MatcherAssert.assertThat(
                "the unrelated entry survives in L2",
                admin.l2TtlMillis(List.of(other.flat())).get(5, TimeUnit.SECONDS)
                    .get(other.flat()) > 0L,
                new IsEqual<>(true)
            );
            admin.invalidateCounted(other).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void uploadInvalidationSweepsL2EntriesThisNodeNeverCached() throws Exception {
        try (ValkeyConnection conn = NegativeCacheL2AdminTest.connection()) {
            final String name = "qa-ct-" + UUID.randomUUID();
            final NegativeCacheKey key = new NegativeCacheKey("qa_ct_group", "npm-group", name, "1.0.0/x.tgz");
            NegativeCacheL2AdminTest.node(conn).cacheNotFound(key);
            final NegativeCache uploader = NegativeCacheL2AdminTest.node(conn);
            NegativeCacheL2AdminTest.await(
                () -> uploader.l2TtlMillis(List.of(key.flat())).get(5, TimeUnit.SECONDS)
                    .get(key.flat()) > 0L
            );
            uploader.invalidateByArtifactName(name);
            NegativeCacheL2AdminTest.await(
                () -> uploader.l2TtlMillis(List.of(key.flat())).get(5, TimeUnit.SECONDS)
                    .get(key.flat()) == -2L
            );
        }
    }

    /**
     * A two-tier cache bound to the shared connection (one "node").
     *
     * @param conn Connection
     * @return Cache
     */
    private static NegativeCache node(final ValkeyConnection conn) {
        return new NegativeCache(
            new NegativeCacheConfig(
                Duration.ofMinutes(5), 1000, true, 1000, Duration.ofMinutes(5),
                1000, Duration.ofMinutes(5)
            ),
            null, conn.async()
        );
    }

    /**
     * Connection from the environment.
     *
     * @return Connection
     */
    private static ValkeyConnection connection() {
        return new ValkeyConnection(
            System.getenv("VALKEY_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379")),
            Duration.ofSeconds(2)
        );
    }

    /**
     * Poll a condition (fire-and-forget writes land asynchronously).
     *
     * @param cond Condition
     * @throws Exception On timeout or failure
     */
    private static void await(final Check cond) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10s");
            }
            Thread.sleep(50L);
        }
    }

    /**
     * Polled condition.
     */
    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }
}
