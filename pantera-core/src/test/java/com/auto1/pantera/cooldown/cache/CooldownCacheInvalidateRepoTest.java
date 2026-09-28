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
package com.auto1.pantera.cooldown.cache;

import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link CooldownCache#invalidateRepo(String)} and {@link CooldownCache#clear()}
 * forget decisions without ever turning a block into "allowed": the next
 * lookup is decided again by the database.
 *
 * @since 2.2.9
 */
final class CooldownCacheInvalidateRepoTest {

    @Test
    void invalidateRepoRecomputesOnlyThatRepositoryFromTheDatabase() throws Exception {
        final CooldownCache cache = new CooldownCache(10_000, Duration.ofHours(24), null);
        cache.put("npm-a", "lodash", "4.17.21", true);
        cache.put("npm-ab", "lodash", "4.17.21", true);
        cache.invalidateRepo("npm-a");
        final AtomicInteger calls = new AtomicInteger();
        final boolean own = cache.isBlocked(
            "npm-a", "lodash", "4.17.21",
            () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }
        ).get(5, TimeUnit.SECONDS);
        final boolean sibling = cache.isBlocked(
            "npm-ab", "lodash", "4.17.21",
            () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the database's block must apply after invalidation", own, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a sibling repository keeps its cached decision", sibling, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "only the invalidated repository consults the database",
            calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void invalidateRepoDeletesL2DecisionsInsteadOfAllowingThem() throws Exception {
        try (ValkeyConnection conn = connection()) {
            final CooldownCache cache = new CooldownCache(conn);
            final String repo = "qa-b34-invalidate-" + System.nanoTime();
            final String other = repo + "-other";
            cache.putBlocked(repo, "lodash", "4.17.21", Instant.now().plusSeconds(600));
            cache.putBlocked(other, "lodash", "4.17.21", Instant.now().plusSeconds(600));
            final String own = cache.blockKey(repo, "lodash", "4.17.21");
            final String sibling = cache.blockKey(other, "lodash", "4.17.21");
            awaitValue(conn, own, "true");
            awaitValue(conn, sibling, "true");
            cache.invalidateRepo(repo);
            awaitValue(conn, own, null);
            MatcherAssert.assertThat(
                "the sibling repository's L2 block is untouched",
                value(conn, sibling), new IsEqual<>("true")
            );
            conn.async().del(sibling).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void clearDeletesL2DecisionsInsteadOfAllowingThem() throws Exception {
        try (ValkeyConnection conn = connection()) {
            final CooldownCache cache = new CooldownCache(conn);
            final String repo = "qa-b34-clear-" + System.nanoTime();
            cache.putBlocked(repo, "lodash", "4.17.21", Instant.now().plusSeconds(600));
            final String key = cache.blockKey(repo, "lodash", "4.17.21");
            awaitValue(conn, key, "true");
            cache.clear();
            awaitValue(conn, key, null);
        }
    }

    private static ValkeyConnection connection() {
        return new ValkeyConnection(
            System.getenv("VALKEY_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379")),
            Duration.ofSeconds(2)
        );
    }

    /**
     * Poll until the key holds the expected value (null = absent); the L2
     * writes and deletes are fire-and-forget.
     */
    private static void awaitValue(
        final ValkeyConnection conn, final String key, final String expected
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String actual = value(conn, key);
        while (System.nanoTime() < deadline
            && !java.util.Objects.equals(actual, expected)) {
            Thread.sleep(20L);
            actual = value(conn, key);
        }
        MatcherAssert.assertThat(
            "L2 value of " + key, actual, new IsEqual<>(expected)
        );
    }

    private static String value(final ValkeyConnection conn, final String key)
        throws Exception {
        final byte[] raw = conn.async().get(key).get(2, TimeUnit.SECONDS);
        return raw == null ? null : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
