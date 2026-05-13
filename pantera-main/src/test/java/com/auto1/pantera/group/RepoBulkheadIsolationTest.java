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
package com.auto1.pantera.group;

import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.resilience.BulkheadLimits;
import com.auto1.pantera.http.resilience.RepoBulkhead;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Isolation test for per-repo bulkheads (WI-09).
 *
 * <p>Saturate repo A's bulkhead; verify repo B's {@link RepoBulkhead#run(java.util.function.Supplier)}
 * still succeeds immediately. This proves that per-repo blast radius works:
 * one misbehaving repository does not starve another.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class RepoBulkheadIsolationTest {

    @Test
    void saturatedRepoADoesNotBlockRepoB() throws Exception {
        final int maxConcurrent = 5;
        final BulkheadLimits limits = new BulkheadLimits(
            maxConcurrent, 100, Duration.ofSeconds(1)
        );
        final RepoBulkhead bulkheadA = new RepoBulkhead(
            "repo-A", limits, ForkJoinPool.commonPool()
        );
        final RepoBulkhead bulkheadB = new RepoBulkhead(
            "repo-B", limits, ForkJoinPool.commonPool()
        );

        // Saturate repo A: hold all permits with uncompleted futures
        final List<CompletableFuture<Result<String>>> blockersA = new ArrayList<>();
        for (int i = 0; i < maxConcurrent; i++) {
            final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
            bulkheadA.run(() -> blocker);
            blockersA.add(blocker);
        }
        assertEquals(maxConcurrent, bulkheadA.activeCount(),
            "Repo A must be fully saturated");

        // Repo A is now full - next request to A must be rejected
        final Result<String> rejectedA = bulkheadA.run(
            () -> CompletableFuture.completedFuture(Result.ok("should-not-reach"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Err.class, rejectedA,
            "Repo A must reject when saturated");
        final Fault faultA = ((Result.Err<String>) rejectedA).fault();
        assertInstanceOf(Fault.Overload.class, faultA,
            "Rejection must be Overload");
        assertEquals("repo-A", ((Fault.Overload) faultA).resource(),
            "Overload must name repo-A");

        // Repo B must still succeed immediately
        assertEquals(0, bulkheadB.activeCount(),
            "Repo B must have zero active requests");
        final Result<String> okB = bulkheadB.run(
            () -> CompletableFuture.completedFuture(Result.ok("repo-B-ok"))
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, okB,
            "Repo B must succeed while repo A is saturated");
        assertEquals("repo-B-ok", ((Result.Ok<String>) okB).value(),
            "Repo B must return the expected value");

        // Clean up repo A blockers
        for (final CompletableFuture<Result<String>> b : blockersA) {
            b.complete(Result.ok("done"));
        }
    }

    @Test
    void independentDrainExecutors() {
        final BulkheadLimits limits = BulkheadLimits.defaults();
        final RepoBulkhead bulkheadA = new RepoBulkhead(
            "repo-A", limits, ForkJoinPool.commonPool()
        );
        final RepoBulkhead bulkheadB = new RepoBulkhead(
            "repo-B", limits, ForkJoinPool.commonPool()
        );

        // Each bulkhead must have its own drain executor instance
        assert bulkheadA.drainExecutor() != bulkheadB.drainExecutor()
            : "Each repo must have an independent drain executor";

        // Each starts with zero drops
        assertEquals(0, bulkheadA.drainDropCount(),
            "Repo A drain drops must start at zero");
        assertEquals(0, bulkheadB.drainDropCount(),
            "Repo B drain drops must start at zero");
    }
}
