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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link FilteredMetadataCache#invalidateByPackageName(String)} on a real
 * Valkey: the L2 drop must be independent of what the local L1 happens to
 * hold, and must not need a walk over the keyspace.
 *
 * <p>Background (the 2.2.6 "npm metadata never refreshes" incident): the
 * old implementation issued the L2 {@code DEL} only for keys matched by a
 * scan of L1. By the time a background packument refresh fired the
 * invalidation hook, the envelope's short-lived L1 twin was typically
 * already evicted — so the stale envelope survived in Valkey for the full
 * L2 TTL and was re-promoted into L1 on every serve, hiding the freshly
 * refreshed packument for up to 24 h. 2.2.7 replaced that with a cursor
 * {@code SCAN} of the whole keyspace, which timed out in production; since
 * 2.2.14 the envelope keys of a package come from its index SET, written
 * atomically with each envelope.</p>
 *
 * <p>These tests write envelopes through one cache instance and invalidate
 * through another with a COLD L1 (nothing ever loaded through it), and
 * require the keys to be gone. Valkey-gated — same convention as
 * {@code FilteredMetadataCacheClearL2Test}; the ungated twin is
 * {@code FilteredMetadataCacheIndexedInvalidationTest}.</p>
 *
 * @since 2.2.7
 */
final class FilteredMetadataCacheL2SweepTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void dropsL2EntriesTheL1NeverHeld() throws Exception {
        try (ValkeyConnection conn = connect()) {
            final String pkg = "sweep-openai-" + UUID.randomUUID();
            final String other = "sweep-not-openai-" + UUID.randomUUID();
            final FilteredMetadataCache writer = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final List<String> victims = List.of(
                write(conn, writer, "npm-proxy", "sweep-repo-a", "full", pkg),
                write(conn, writer, "npm-proxy", "sweep-repo-a", "abbreviated", pkg),
                write(conn, writer, "npm-group", "sweep-repo-b", "full", pkg)
            );
            final String survivor = write(conn, writer, "npm-proxy", "sweep-repo-a", "full", other);
            MatcherAssert.assertThat(
                "every envelope is listed in the index of its package",
                conn.async().scard(FilteredMetadataCache.indexKey(pkg)).get(2, TimeUnit.SECONDS),
                new IsEqual<>(3L)
            );
            MatcherAssert.assertThat(
                "the index outlives the envelopes it lists",
                conn.async().ttl(FilteredMetadataCache.indexKey(pkg)).get(2, TimeUnit.SECONDS)
                    >= conn.async().ttl(victims.get(0)).get(2, TimeUnit.SECONDS),
                new IsEqual<>(true)
            );

            // COLD L1: this instance never loaded anything, mirroring the
            // production state at hook time (L1 twin already evicted).
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final int l1Dropped = cache.invalidateByPackageName(pkg);

            MatcherAssert.assertThat(
                "nothing was in L1, so the L1 count must be zero",
                l1Dropped, new IsEqual<>(0)
            );
            awaitGone(conn, victims);
            MatcherAssert.assertThat(
                "an envelope of a different package must survive",
                conn.async().exists(survivor).get(2, TimeUnit.SECONDS),
                new IsEqual<>(1L)
            );
            MatcherAssert.assertThat(
                "the emptied index is gone too",
                conn.async().exists(FilteredMetadataCache.indexKey(pkg)).get(2, TimeUnit.SECONDS),
                new IsEqual<>(0L)
            );
            cache.invalidatePackageAwaiting(other).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void dropsL2InL2OnlyMode() throws Exception {
        try (ValkeyConnection conn = connect()) {
            final String pkg = "sweep-axios-" + UUID.randomUUID();
            // l1Size == 0 with a Valkey connection = L2-only mode.
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                0, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final String key = write(conn, cache, "npm-proxy", "sweep-l2only", "full", pkg);
            cache.invalidateByPackageName(pkg);
            awaitGone(conn, List.of(key));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void repositoryScopedInvalidationLeavesOtherRepositories() throws Exception {
        try (ValkeyConnection conn = connect()) {
            final String pkg = "sweep-scoped-" + UUID.randomUUID();
            final FilteredMetadataCache writer = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final String gone = write(conn, writer, "npm-proxy", "sweep-repo-a", "full", pkg);
            final String kept = write(conn, writer, "npm-proxy", "sweep-repo-b", "full", pkg);
            new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            ).invalidate("npm-proxy", "sweep-repo-a", pkg);
            awaitGone(conn, List.of(gone));
            MatcherAssert.assertThat(
                "the other repository's envelope stays, still listed",
                conn.async().smembers(FilteredMetadataCache.indexKey(pkg))
                    .get(2, TimeUnit.SECONDS).size()
                    + conn.async().exists(kept).get(2, TimeUnit.SECONDS),
                new IsEqual<>(2L)
            );
            writer.invalidatePackageAwaiting(pkg).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void envelopeOfThePreviousKeyGenerationIsNotReadAndIsDeleted() throws Exception {
        try (ValkeyConnection conn = connect()) {
            final String pkg = "sweep-legacy-" + UUID.randomUUID();
            // What a 2.2.13 node wrote: no generation marker, no index entry.
            final String legacy = "metadata:npm-proxy:sweep-repo-a:full:" + pkg;
            conn.async().setex(
                legacy, 120L, "stale".getBytes(StandardCharsets.UTF_8)
            ).get(2, TimeUnit.SECONDS);
            final FilteredMetadataCache cache = new FilteredMetadataCache(
                100, Duration.ofMinutes(5), Duration.ofMinutes(5), conn
            );
            final byte[] served = cache.getEntry(
                "npm-proxy", "sweep-repo-a", "full", pkg,
                () -> java.util.concurrent.CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        "fresh".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)
                    )
                )
            ).get(5, TimeUnit.SECONDS).data();
            MatcherAssert.assertThat(
                "an envelope no index lists must not be served",
                new String(served, StandardCharsets.UTF_8), new IsEqual<>("fresh")
            );
            cache.invalidatePackageAwaiting(pkg).get(5, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the invalidation deletes it for the nodes that still read it",
                conn.async().exists(legacy).get(2, TimeUnit.SECONDS),
                new IsEqual<>(0L)
            );
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
            final String key =
                FilteredMetadataCache.cacheKey("npm-proxy", "rt-repo", "full", "rt-pkg");
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
            final String legacyKey =
                FilteredMetadataCache.cacheKey("npm-proxy", "rt-repo", "full", "rt-legacy");
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
     * Connect to the Valkey named by the environment.
     *
     * @return Connection
     */
    private static ValkeyConnection connect() {
        return new ValkeyConnection(
            System.getenv("VALKEY_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379")),
            Duration.ofSeconds(2)
        );
    }

    /**
     * Compute an envelope through the cache and wait for its L2 write.
     *
     * @param conn Connection
     * @param cache Cache that writes
     * @param type Repository type
     * @param repo Repository name
     * @param variant Variant
     * @param pkg Package name
     * @return The envelope key
     * @throws Exception On failure
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static String write(
        final ValkeyConnection conn, final FilteredMetadataCache cache, final String type,
        final String repo, final String variant, final String pkg
    ) throws Exception {
        cache.getEntry(
            type, repo, variant, pkg,
            () -> java.util.concurrent.CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(
                    "envelope".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)
                )
            )
        ).get(5, TimeUnit.SECONDS);
        final String key = FilteredMetadataCache.cacheKey(type, repo, variant, pkg);
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (conn.async().exists(key).get(2, TimeUnit.SECONDS) == 0L) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("L2 envelope write never landed: " + key);
            }
        }
        return key;
    }

    /**
     * Poll until every key is deleted (the drop is asynchronous) or fail
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
