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
package com.auto1.pantera.http.resilience;

import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RepoBulkhead}. Covers the WI-09 DoD requirements:
 * <ul>
 *   <li>{@link #rejectsWhenSaturated()} - acquire maxConcurrent permits; next run() returns Fault.Overload</li>
 *   <li>{@link #releasesPermitOnSuccess()} - acquire, complete, next run() succeeds</li>
 *   <li>{@link #releasesPermitOnFailure()} - acquire, op throws, permit still released</li>
 *   <li>{@link #activeCountTracksPermits()} - activeCount reflects held permits</li>
 *   <li>{@link #defaultLimitsAreReasonable()} - BulkheadLimits.defaults() values are sane</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class RepoBulkheadTest {

    @Test
    void rejectsWhenSaturated() throws Exception {
        final int maxConcurrent = 3;
        final BulkheadLimits limits = new BulkheadLimits(
            maxConcurrent, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a", limits, ForkJoinPool.commonPool()
        );
        // Hold maxConcurrent permits with uncompleted futures
        final List<CompletableFuture<Result<String>>> blockers = new ArrayList<>();
        for (int i = 0; i < maxConcurrent; i++) {
            final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
            bulkhead.run(() -> blocker);
            blockers.add(blocker);
        }
        assertEquals(maxConcurrent, bulkhead.activeCount(),
            "All permits should be held");
        // Next run must be rejected
        final Result<String> rejected = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("should-not-reach"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Err.class, rejected, "Must be rejected");
        final Fault fault = ((Result.Err<String>) rejected).fault();
        assertInstanceOf(Fault.Overload.class, fault, "Must be Overload fault");
        final Fault.Overload overload = (Fault.Overload) fault;
        assertEquals("repo-a", overload.resource(), "Fault must carry the repo name");
        assertEquals(Duration.ofSeconds(1), overload.retryAfter(),
            "Fault must carry the configured retry-after");
        // Clean up blockers
        for (final CompletableFuture<Result<String>> b : blockers) {
            b.complete(Result.ok("done"));
        }
    }

    @Test
    void releasesPermitOnSuccess() throws Exception {
        final BulkheadLimits limits = new BulkheadLimits(
            1, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-b", limits, ForkJoinPool.commonPool()
        );
        // Acquire the single permit and complete immediately
        final Result<String> first = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("ok"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, first, "First call must succeed");
        assertEquals(0, bulkhead.activeCount(),
            "Permit must be released after success");
        // Next run must also succeed (permit was released)
        final Result<String> second = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("ok2"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, second, "Second call must succeed after permit release");
    }

    @Test
    void releasesPermitOnFailure() throws Exception {
        final BulkheadLimits limits = new BulkheadLimits(
            1, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-c", limits, ForkJoinPool.commonPool()
        );
        // Acquire the single permit; the future completes exceptionally
        final CompletableFuture<Result<String>> failing = new CompletableFuture<>();
        final CompletionStage<Result<String>> stage = bulkhead.run(() -> failing);
        assertEquals(1, bulkhead.activeCount(),
            "Permit must be held while in-flight");
        failing.completeExceptionally(new RuntimeException("boom"));
        // Wait for the whenComplete to fire
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (final Exception ignored) {
            // Expected - the stage completed exceptionally
        }
        assertEquals(0, bulkhead.activeCount(),
            "Permit must be released even on exceptional completion");
        // Next run must succeed (permit was released)
        final Result<String> next = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("recovered"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, next,
            "Next call must succeed after exceptional permit release");
    }

    @Test
    void activeCountTracksPermits() throws Exception {
        final int maxConcurrent = 5;
        final BulkheadLimits limits = new BulkheadLimits(
            maxConcurrent, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-d", limits, ForkJoinPool.commonPool()
        );
        assertEquals(0, bulkhead.activeCount(), "Initially zero active");
        final List<CompletableFuture<Result<String>>> blockers = new ArrayList<>();
        for (int i = 0; i < maxConcurrent; i++) {
            final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
            bulkhead.run(() -> blocker);
            blockers.add(blocker);
            assertEquals(i + 1, bulkhead.activeCount(),
                "Active count must track acquired permits");
        }
        // Complete them one by one and verify count decreases
        for (int i = 0; i < maxConcurrent; i++) {
            blockers.get(i).complete(Result.ok("done-" + i));
            // Small delay to allow whenComplete to fire
            Thread.sleep(10);
            assertEquals(maxConcurrent - i - 1, bulkhead.activeCount(),
                "Active count must decrease as permits are released");
        }
    }

    @Test
    void defaultLimitsAreReasonable() {
        final BulkheadLimits defaults = BulkheadLimits.defaults();
        assertEquals(200, defaults.maxConcurrent(),
            "Default maxConcurrent should be 200");
        assertEquals(1000, defaults.maxQueueDepth(),
            "Default maxQueueDepth should be 1000");
        assertEquals(Duration.ofSeconds(1), defaults.retryAfter(),
            "Default retryAfter should be 1 second");
    }

    @Test
    void repoNameIsAccessible() {
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "my-repo", BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        assertEquals("my-repo", bulkhead.repo());
    }

    @Test
    void drainExecutorIsAvailable() {
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "drain-test", BulkheadLimits.defaults(), ForkJoinPool.commonPool()
        );
        assertTrue(bulkhead.drainExecutor() != null,
            "Drain executor must be non-null");
        assertEquals(0, bulkhead.drainDropCount(),
            "Initial drain drop count must be zero");
    }

    @Test
    void synchronousSupplierExceptionReleasesPermit() throws Exception {
        final BulkheadLimits limits = new BulkheadLimits(
            1, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-sync-ex", limits, ForkJoinPool.commonPool()
        );
        // Supplier throws synchronously before returning a CompletionStage
        try {
            bulkhead.run(() -> {
                throw new RuntimeException("sync boom");
            }).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (final Exception ignored) {
            // Expected
        }
        assertEquals(0, bulkhead.activeCount(),
            "Permit must be released even when supplier throws synchronously");
        // Verify next call succeeds
        final Result<String> next = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("recovered"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, next,
            "Next call must succeed after synchronous-exception permit release");
    }
}
