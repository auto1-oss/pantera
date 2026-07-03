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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Behavioural test for {@link FilteredMetadataCache#clear()}'s L2 wipe.
 *
 * <p>Background: {@code clear()} historically only flushed the in-memory
 * L1 tier. When cooldown settings changed (admin toggles enabled / changes
 * minimum_allowed_age / adds an override), {@code MetadataFilterService.clearAll()}
 * called {@code clear()} but L2 (Valkey) kept stale envelope bytes for up
 * to the L2 TTL (12 h). After a restart, L1 re-hydrated from L2 — so the
 * "clear" never actually took effect for envelope policy changes.
 *
 * <p>This test asserts {@code clear()} now wipes both tiers: it seeds
 * {@code metadata:*} keys in a real Valkey instance, calls {@code clear()},
 * and verifies the keys are gone. Valkey-gated so it skips when the
 * harness has no broker — same convention as
 * {@code ValkeyConnectionTest}.
 *
 * @since 2.2.0
 */
final class FilteredMetadataCacheClearL2Test {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void clearWipesMetadataKeysFromL2() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            final byte[] seed = "x".getBytes(StandardCharsets.UTF_8);
            final List<String> seedKeys = List.of(
                "metadata:test-clear-l2:repo-a:pkg-1",
                "metadata:test-clear-l2:repo-a:pkg-2",
                "metadata:test-clear-l2:repo-b:pkg-3"
            );
            for (final String key : seedKeys) {
                conn.async().setex(key, 60L, seed).get(2, TimeUnit.SECONDS);
            }
            MatcherAssert.assertThat(
                "Seeded keys must be visible before clear()",
                Long.valueOf(conn.async().exists(seedKeys.toArray(new String[0]))
                    .get(2, TimeUnit.SECONDS)),
                new IsEqual<>(Long.valueOf(seedKeys.size()))
            );

            final FilteredMetadataCache cache = new FilteredMetadataCache(conn);
            cache.clear();
            // L2 wipe is async (fire-and-forget keys -> del chain); allow
            // a short bounded wait for the round-trip to complete before
            // re-checking. 5 s is generous on any localhost Valkey.
            final long deadline = System.nanoTime()
                + Duration.ofSeconds(5).toNanos();
            long remaining = seedKeys.size();
            while (System.nanoTime() < deadline && remaining > 0L) {
                remaining = conn.async()
                    .exists(seedKeys.toArray(new String[0]))
                    .get(2, TimeUnit.SECONDS);
                if (remaining == 0L) {
                    break;
                }
                Thread.sleep(50L);
            }
            MatcherAssert.assertThat(
                "clear() must delete every metadata:* key from L2",
                Long.valueOf(remaining),
                new IsEqual<>(Long.valueOf(0L))
            );
        }
    }
}
