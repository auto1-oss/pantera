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

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that the CooldownCache inflight map is properly cleaned up
 * on exceptional completion, cancellation, and timeout.
 *
 * @since 2.2.0
 */
final class CooldownCacheInflightLeakTest {

    private CooldownCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new CooldownCache(10_000, Duration.ofHours(24), null);
    }

    @Test
    void inflightMapEmptyAfterExceptionalCompletion() throws Exception {
        // Submit a query that fails with an exception
        final CompletableFuture<Boolean> result = this.cache.isBlocked(
            "test-repo", "artifact", "1.0.0",
            () -> CompletableFuture.failedFuture(
                new RuntimeException("db query failed")
            )
        );

        // Wait for the future to complete (exceptionally)
        assertThrows(ExecutionException.class, result::get);

        // The inflight map must be empty after the exception
        final ConcurrentMap<String, ?> inflight = getInflightMap(this.cache);
        assertThat(
            "Inflight map should be empty after exceptional completion",
            inflight.size(), equalTo(0)
        );
    }

    @Test
    void inflightMapEmptyAfterCancellation() throws Exception {
        // Submit a query that blocks indefinitely
        final CompletableFuture<Boolean> blocker = new CompletableFuture<>();
        final CompletableFuture<Boolean> result = this.cache.isBlocked(
            "test-repo", "artifact", "2.0.0",
            () -> blocker
        );

        // Cancel the query
        result.cancel(true);

        // Let the cancellation propagate
        Thread.sleep(50);

        // The inflight map must be empty after cancellation
        final ConcurrentMap<String, ?> inflight = getInflightMap(this.cache);
        assertThat(
            "Inflight map should be empty after cancellation",
            inflight.size(), equalTo(0)
        );
    }

    @Test
    void inflightMapEmptyAfterSuccessfulCompletion() throws Exception {
        // Submit a normal query
        final CompletableFuture<Boolean> result = this.cache.isBlocked(
            "test-repo", "artifact", "3.0.0",
            () -> CompletableFuture.completedFuture(true)
        );

        result.get();

        final ConcurrentMap<String, ?> inflight = getInflightMap(this.cache);
        assertThat(
            "Inflight map should be empty after successful completion",
            inflight.size(), equalTo(0)
        );
    }

    @Test
    void inflightMapEmptyAfterTimeout() throws Exception {
        // Submit a query that never completes (the orTimeout safety net should fire)
        final CompletableFuture<Boolean> neverCompletes = new CompletableFuture<>();
        final CompletableFuture<Boolean> result = this.cache.isBlocked(
            "test-repo", "artifact", "4.0.0",
            () -> neverCompletes
        );

        // The future should eventually time out (30s orTimeout in production,
        // but we verify the inflight entry is removed on timeout)
        // For test speed, cancel after a brief wait to simulate timeout behaviour
        result.orTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS);

        try {
            result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final ExecutionException | TimeoutException ignored) {
            // Expected
        }

        // Let cleanup propagate
        Thread.sleep(50);

        final ConcurrentMap<String, ?> inflight = getInflightMap(this.cache);
        assertThat(
            "Inflight map should be empty after timeout",
            inflight.size(), equalTo(0)
        );
    }

    /**
     * Reflectively access the inflight map for assertions.
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, ?> getInflightMap(final CooldownCache cache)
        throws Exception {
        final Field field = CooldownCache.class.getDeclaredField("inflight");
        field.setAccessible(true);
        return (ConcurrentMap<String, ?>) field.get(cache);
    }
}
