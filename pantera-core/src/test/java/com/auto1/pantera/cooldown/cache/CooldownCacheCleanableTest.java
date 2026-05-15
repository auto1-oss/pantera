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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for the {@link CooldownCache#invalidate(String)} /
 * {@link CooldownCache#invalidateAll()} Cleanable implementation added for
 * cross-instance pub/sub fan-out. These are the receive-side handlers: they
 * must drop the local L1 entry without re-publishing (re-publish would
 * create an invalidation loop).
 *
 * @since 2.2.0
 */
final class CooldownCacheCleanableTest {

    private CooldownCache cache;

    @BeforeEach
    void setUp() {
        // Single-tier (no Valkey) — keeps the test focused on L1 behavior
        this.cache = new CooldownCache(10_000, Duration.ofHours(24), null);
    }

    @Test
    void invalidateByKeyDropsL1Entry() throws Exception {
        // Seed L1 with a blocked decision via the public put() API
        this.cache.put("npm", "lodash", "4.17.21", true);
        final String key = this.cache.blockKey("npm", "lodash", "4.17.21");
        // Pre-condition: a subsequent isBlocked() must NOT call the loader
        // because L1 holds the decision.
        final AtomicInteger loaderCalls = new AtomicInteger(0);
        final boolean before = this.cache.isBlocked(
            "npm", "lodash", "4.17.21",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        ).get();
        assertThat("L1 must hold the seeded blocked decision", before, equalTo(true));
        assertThat("L1 hit must skip the loader", loaderCalls.get(), equalTo(0));
        // Act: receive-side invalidation — drop the single L1 entry
        this.cache.invalidate(key);
        // Post-condition: next isBlocked() now misses L1 and falls back to
        // the loader, which we can return false from to verify the L1 entry
        // really was gone.
        final boolean after = this.cache.isBlocked(
            "npm", "lodash", "4.17.21",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        ).get();
        assertThat("L1 miss must call the loader exactly once", loaderCalls.get(), equalTo(1));
        assertThat("loader's value must surface after L1 drop", after, equalTo(false));
    }

    @Test
    void invalidateAllDropsAllL1Entries() throws Exception {
        // Seed two L1 entries
        this.cache.put("npm", "lodash", "4.17.21", true);
        this.cache.put("npm", "axios", "1.6.0", true);
        // Act: bulk drop
        this.cache.invalidateAll();
        // Post-condition: both keys must miss L1 (loader fires for each)
        final AtomicInteger loaderCalls = new AtomicInteger(0);
        this.cache.isBlocked("npm", "lodash", "4.17.21",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        ).get();
        this.cache.isBlocked("npm", "axios", "1.6.0",
            () -> {
                loaderCalls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        ).get();
        assertThat(
            "both L1 entries must be gone after invalidateAll",
            loaderCalls.get(), equalTo(2)
        );
    }
}
