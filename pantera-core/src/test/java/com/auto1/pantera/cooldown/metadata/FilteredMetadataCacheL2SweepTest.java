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
import java.util.concurrent.TimeUnit;

/**
 * Behavioural test for the 2.2.7 fix of
 * {@link FilteredMetadataCache#invalidateByPackageName(String)}: the Valkey
 * L2 sweep must be independent of what the local L1 happens to hold.
 *
 * <p>Background (the 2.2.6 "npm metadata never refreshes" incident): the
 * old implementation issued the L2 {@code DEL} only for keys matched by a
 * scan of L1. By the time a background packument refresh fired the
 * invalidation hook, the envelope's short-lived L1 twin was typically
 * already evicted — so the stale envelope survived in Valkey for the full
 * L2 TTL and was re-promoted into L1 on every serve, hiding the freshly
 * refreshed packument for up to 24 h.</p>
 *
 * <p>These tests seed envelope keys straight into Valkey with a COLD L1
 * (nothing ever loaded through the cache instance), invalidate by package
 * name, and require the keys to be gone. Valkey-gated — same convention as
 * {@code FilteredMetadataCacheClearL2Test}.</p>
 *
 * @since 2.2.7
 */
final class FilteredMetadataCacheL2SweepTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void sweepsL2EntriesTheL1NeverHeld() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            final byte[] seed = "stale-envelope".getBytes(StandardCharsets.UTF_8);
            final List<String> victims = List.of(
                "metadata:npm-proxy:sweep-repo-a:sweep-openai",
                "metadata:npm-group:sweep-repo-b:sweep-openai",
                "metadata:npm-proxy:sweep-repo-a:full:sweep-openai",
                "metadata:npm-proxy:sweep-repo-a:abbreviated:sweep-openai"
            );
            final String survivor = "metadata:npm-proxy:sweep-repo-a:sweep-not-openai";
            for (final String key : victims) {
                conn.async().setex(key, 120L, seed).get(2, TimeUnit.SECONDS);
            }
            conn.async().setex(survivor, 120L, seed).get(2, TimeUnit.SECONDS);

            // COLD L1: this instance never loaded anything, mirroring the
            // production state at hook time (L1 twin already evicted).
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final int l1Dropped = cache.invalidateByPackageName("sweep-openai");

            MatcherAssert.assertThat(
                "nothing was in L1, so the L1 count must be zero",
                l1Dropped, new IsEqual<>(0)
            );
            awaitGone(conn, victims);
            MatcherAssert.assertThat(
                "an envelope of a different package must survive the sweep",
                conn.async().exists(survivor).get(2, TimeUnit.SECONDS),
                new IsEqual<>(1L)
            );
            conn.async().del(survivor).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void sweepsL2InL2OnlyMode() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            final String key = "metadata:npm-proxy:sweep-l2only:sweep-axios";
            conn.async().setex(
                key, 120L, "stale".getBytes(StandardCharsets.UTF_8)
            ).get(2, TimeUnit.SECONDS);

            // l1Size == 0 with a Valkey connection = L2-only mode: the old
            // implementation had NO key source at all here and the
            // invalidation was a total no-op.
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                0, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            cache.invalidateByPackageName("sweep-axios");
            awaitGone(conn, List.of(key));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void compressesL2ValuesAndReadsLegacyRaw() throws Exception {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(
            System.getenv().getOrDefault("VALKEY_PORT", "6379")
        );
        try (ValkeyConnection conn = new ValkeyConnection(
            host, port, Duration.ofSeconds(2)
        )) {
            // >1KB so the codec compresses; repetitive so it visibly shrinks.
            final byte[] original = ("{\"versions\":{"
                + "\"1.0.0\":{\"x\":\"" + "y".repeat(4096) + "\"}}}"
            ).getBytes(StandardCharsets.UTF_8);
            final String key = "metadata:npm-proxy:rt-repo:full:rt-pkg";
            conn.async().del(key).get(2, TimeUnit.SECONDS);

            final FilteredMetadataCache writer = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            writer.getEntry(
                "npm-proxy", "rt-repo", "full", "rt-pkg",
                () -> java.util.concurrent.CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        original, Duration.ofMinutes(5)
                    )
                )
            ).get(5, TimeUnit.SECONDS);
            // The L2 write is async — poll for the key.
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            byte[] stored = null;
            while (stored == null) {
                stored = conn.async().get(key).get(2, TimeUnit.SECONDS);
                if (stored == null && System.nanoTime() > deadline) {
                    throw new AssertionError("L2 envelope write never landed");
                }
            }
            MatcherAssert.assertThat(
                "the stored L2 value must be gzip-compressed (magic bytes) and smaller",
                (stored[0] & 0xFF) == 0x1F && stored.length < original.length,
                new IsEqual<>(true)
            );

            // A COLD instance (empty L1) must decode the L2 hit back to the
            // original bytes — the loader must not run.
            final FilteredMetadataCache reader = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final byte[] served = reader.getEntry(
                "npm-proxy", "rt-repo", "full", "rt-pkg",
                () -> {
                    throw new AssertionError("loader must not run on an L2 hit");
                }
            ).get(5, TimeUnit.SECONDS).data();
            MatcherAssert.assertThat(
                "the decoded L2 hit must be byte-identical to the original envelope",
                served, new IsEqual<>(original)
            );

            // Legacy (pre-compression) raw value: must pass through unchanged.
            final String legacyKey = "metadata:npm-proxy:rt-repo:full:rt-legacy";
            final byte[] legacy = "{\"legacy\":true}".getBytes(StandardCharsets.UTF_8);
            conn.async().setex(legacyKey, 60L, legacy).get(2, TimeUnit.SECONDS);
            final byte[] legacyServed = reader.getEntry(
                "npm-proxy", "rt-repo", "full", "rt-legacy",
                () -> {
                    throw new AssertionError("loader must not run on a legacy L2 hit");
                }
            ).get(5, TimeUnit.SECONDS).data();
            MatcherAssert.assertThat(
                "a raw legacy L2 value must be served unchanged",
                legacyServed, new IsEqual<>(legacy)
            );
            conn.async().del(key, legacyKey).get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Poll until every key is deleted (the sweep is async SCAN+DEL) or fail
     * after a bounded deadline — never a bare sleep-and-hope.
     */
    private static void awaitGone(
        final ValkeyConnection conn, final List<String> keys
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        long remaining = keys.size();
        while (remaining > 0L) {
            remaining = conn.async().exists(keys.toArray(new String[0]))
                .get(2, TimeUnit.SECONDS);
            if (remaining > 0L && System.nanoTime() > deadline) {
                throw new AssertionError(
                    "L2 sweep never deleted " + remaining + " stale envelope key(s)"
                );
            }
        }
    }
}
