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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An invalidation must win over a filter computation that was already running
 * when the block state changed, and a missed invalidation must not pin a
 * stale envelope in L1 until the earliest block expires (days).
 *
 * @since 2.2.9
 */
final class FilteredMetadataCacheStaleWriteTest {

    private static final byte[] STALE = "stale".getBytes(StandardCharsets.UTF_8);

    private static final byte[] FRESH = "fresh".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(10)
    void loadInFlightDuringInvalidateDoesNotRepopulate() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final CompletableFuture<FilteredMetadataCache.CacheEntry> slow = new CompletableFuture<>();
        final CompletableFuture<byte[]> first = cache.get(
            "npm-proxy", "npm_proxy", "pkg", () -> slow
        );
        cache.invalidate("npm-proxy", "npm_proxy", "pkg");
        slow.complete(FilteredMetadataCacheStaleWriteTest.blocked(STALE, Duration.ofDays(3)));
        MatcherAssert.assertThat(
            "the caller that started before the invalidation still gets its answer",
            first.get(5, TimeUnit.SECONDS), new IsEqual<>(STALE)
        );
        final AtomicInteger loads = new AtomicInteger();
        MatcherAssert.assertThat(
            "the next request recomputes instead of reading the pre-invalidation result",
            cache.get("npm-proxy", "npm_proxy", "pkg", () -> {
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(FRESH, Duration.ofHours(1))
                );
            }).get(5, TimeUnit.SECONDS),
            new IsEqual<>(FRESH)
        );
        MatcherAssert.assertThat("exactly one recompute", loads.get(), new IsEqual<>(1));
    }

    @Test
    @Timeout(10)
    void loadInFlightDuringClearDoesNotRepopulate() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final CompletableFuture<FilteredMetadataCache.CacheEntry> slow = new CompletableFuture<>();
        cache.get("npm-proxy", "npm_proxy", "pkg", () -> slow);
        cache.clear();
        slow.complete(FilteredMetadataCacheStaleWriteTest.blocked(STALE, Duration.ofDays(3)));
        MatcherAssert.assertThat(
            cache.get("npm-proxy", "npm_proxy", "pkg", () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(FRESH, Duration.ofHours(1))
            )).get(5, TimeUnit.SECONDS),
            new IsEqual<>(FRESH)
        );
    }

    @Test
    @Timeout(10)
    void blockedEnvelopeIsRevalidatedAfterL1TtlNotAtBlockExpiry() throws Exception {
        // L1 TTL 200 ms; the envelope's earliest block ends in 3 days.
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMillis(200), Duration.ofMinutes(5), null
        );
        cache.get("npm-proxy", "npm_proxy", "pkg", () -> CompletableFuture.completedFuture(
            FilteredMetadataCacheStaleWriteTest.blocked(STALE, Duration.ofDays(3))
        )).get(5, TimeUnit.SECONDS);
        final CountDownLatch revalidated = new CountDownLatch(1);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        byte[] served = STALE;
        while (!new String(served, StandardCharsets.UTF_8).equals("fresh")
            && System.nanoTime() < deadline) {
            served = cache.get("npm-proxy", "npm_proxy", "pkg", () -> {
                revalidated.countDown();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(FRESH, Duration.ofHours(1))
                );
            }).get(5, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(50);
        }
        MatcherAssert.assertThat(
            "the envelope is recomputed once l1Ttl elapses",
            revalidated.await(0, TimeUnit.SECONDS), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "clients converge on the recomputed envelope",
            served, new IsEqual<>(FRESH)
        );
    }

    @Test
    void blockedEnvelopeIsServedFromL1WithinL1Ttl() throws Exception {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        cache.get("npm-proxy", "npm_proxy", "pkg", () -> CompletableFuture.completedFuture(
            FilteredMetadataCacheStaleWriteTest.blocked(STALE, Duration.ofDays(3))
        )).get(5, TimeUnit.SECONDS);
        final AtomicInteger loads = new AtomicInteger();
        cache.get("npm-proxy", "npm_proxy", "pkg", () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(FRESH, Duration.ofHours(1))
            );
        }).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat("no recompute inside l1Ttl", loads.get(), new IsEqual<>(0));
    }

    private static FilteredMetadataCache.CacheEntry blocked(
        final byte[] data, final Duration until
    ) {
        return FilteredMetadataCache.CacheEntry.withBlockedVersions(
            data, Instant.now().plus(until), Duration.ofHours(24)
        );
    }
}
