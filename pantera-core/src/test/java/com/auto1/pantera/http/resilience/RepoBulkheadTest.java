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
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RepoBulkhead}. Covers the WI-09 DoD requirements:
 * <ul>
 *   <li>{@link #waitsWhenSaturatedAndRunsOnceAPermitIsFree()} - acquire maxConcurrent permits; next run() waits for one</li>
 *   <li>{@link #releasesPermitOnSuccess()} - acquire, complete, next run() succeeds</li>
 *   <li>{@link #releasesPermitOnFailure()} - acquire, op throws, permit still released</li>
 *   <li>{@link #activeCountTracksPermits()} - activeCount reflects held permits</li>
 *   <li>{@link #defaultLimitsAreReasonable()} - BulkheadLimits.defaults() values are sane</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class RepoBulkheadTest {

    @Test
    void waitsWhenSaturatedAndRunsOnceAPermitIsFree() throws Exception {
        // A burst beyond the permits used to be refused on the spot, which a
        // proxy with several remotes reported as 502. The request now waits.
        final int maxConcurrent = 3;
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(maxConcurrent, 100, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool()
        );
        final List<CompletableFuture<Result<String>>> blockers = new ArrayList<>();
        for (int i = 0; i < maxConcurrent; i++) {
            final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
            bulkhead.run(() -> blocker);
            blockers.add(blocker);
        }
        final CompletableFuture<Result<String>> queued = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("served"))
        ).toCompletableFuture();
        assertEquals(maxConcurrent, bulkhead.activeCount(), "All permits are held");
        assertEquals(1, bulkhead.waitingCount(), "The next request waits");
        assertTrue(!queued.isDone(), "and is not answered yet");
        blockers.get(0).complete(Result.ok("done"));
        final Result<String> served = queued.get(10, TimeUnit.SECONDS);
        assertInstanceOf(Result.Ok.class, served, "It runs once a permit is free");
        assertEquals("served", ((Result.Ok<String>) served).value(), "with its own result");
        for (final CompletableFuture<Result<String>> b : blockers) {
            b.complete(Result.ok("done"));
        }
    }

    @Test
    void refusesWithOverloadWhenNoPermitComesWithinTheWaitBound() throws Exception {
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool(),
            Duration.ofMillis(20)
        );
        final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
        bulkhead.run(() -> blocker);
        final Result<String> refused = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("should-not-reach"))
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertInstanceOf(Result.Err.class, refused, "Must be refused");
        final Fault fault = ((Result.Err<String>) refused).fault();
        assertInstanceOf(Fault.Overload.class, fault, "Must be Overload fault");
        final Fault.Overload overload = (Fault.Overload) fault;
        assertEquals("repo-a", overload.resource(), "Fault must carry the repo name");
        assertEquals(Duration.ofSeconds(1), overload.retryAfter(),
            "Fault must carry the configured retry-after");
        blocker.complete(Result.ok("done"));
    }

    @Test
    void refusalAfterTheWaitBoundIsHandedToTheResumeExecutor() throws Exception {
        // The wait bound expires on the single timer thread of the JDK. What
        // follows a refusal (the 503, the walk to the next group member) must
        // not run there: one slow continuation would delay every other bound.
        final AtomicInteger handed = new AtomicInteger();
        final Executor counting = task -> {
            handed.incrementAndGet();
            ForkJoinPool.commonPool().execute(task);
        };
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)),
            counting,
            Duration.ofMillis(20)
        );
        final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
        bulkhead.run(() -> blocker);
        bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("should-not-reach"))
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(1, handed.get());
        blocker.complete(Result.ok("done"));
    }

    @Test
    void refusesAtOnceWhenTheQueueIsFull() throws Exception {
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(1, 1, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool()
        );
        final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
        bulkhead.run(() -> blocker);
        final CompletableFuture<Result<String>> waiting = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("second"))
        ).toCompletableFuture();
        final CompletableFuture<Result<String>> beyond = bulkhead.run(
            () -> CompletableFuture.completedFuture(Result.ok("should-not-reach"))
        ).toCompletableFuture();
        assertTrue(beyond.isDone(), "A request beyond the queue is answered at once");
        assertInstanceOf(
            Fault.Overload.class, ((Result.Err<String>) beyond.get()).fault(),
            "and it is refused as overload"
        );
        blocker.complete(Result.ok("done"));
        assertEquals(
            "second", ((Result.Ok<String>) waiting.get(10, TimeUnit.SECONDS)).value(),
            "The request inside the queue is still served"
        );
    }

    @Test
    void queuedOperationRunsUnderTheLoggingContextOfItsCaller() throws Exception {
        // The permit is released on a thread that serves another request:
        // the queued operation must not run under that request's context.
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool()
        );
        final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
        bulkhead.run(() -> blocker);
        ThreadContext.put("trace.id", "queued-request");
        final CompletableFuture<Result<String>> queued;
        try {
            queued = bulkhead.run(
                () -> CompletableFuture.completedFuture(
                    Result.ok(String.valueOf(ThreadContext.get("trace.id")))
                )
            ).toCompletableFuture();
        } finally {
            ThreadContext.clearMap();
        }
        final Thread releaser = new Thread(
            () -> {
                ThreadContext.put("trace.id", "another-request");
                blocker.complete(Result.ok("done"));
            },
            "releaser"
        );
        releaser.start();
        releaser.join(TimeUnit.SECONDS.toMillis(10));
        final Result<String> seen = queued.get(10, TimeUnit.SECONDS);
        assertEquals("queued-request", ((Result.Ok<String>) seen).value());
    }

    @Test
    void aLongQueueOfInstantOperationsAllRun() throws Exception {
        // Each finished operation hands its permit to the next one. Run
        // inline, two thousand of them would nest two thousand frames deep.
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "repo-a",
            AdaptiveBulkheadLimits.fixed(1, 5000, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool()
        );
        final CompletableFuture<Result<String>> blocker = new CompletableFuture<>();
        bulkhead.run(() -> blocker);
        final List<CompletableFuture<Result<Integer>>> queued = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            final int number = i;
            queued.add(
                bulkhead.<Integer>run(
                    () -> CompletableFuture.completedFuture(Result.ok(number))
                ).toCompletableFuture()
            );
        }
        blocker.complete(Result.ok("done"));
        CompletableFuture.allOf(queued.toArray(new CompletableFuture[0]))
            .get(20, TimeUnit.SECONDS);
        int served = 0;
        for (final CompletableFuture<Result<Integer>> one : queued) {
            if (one.get() instanceof Result.Ok<Integer>) {
                served += 1;
            }
        }
        assertEquals(2000, served, "Every queued operation ran");
        assertEquals(0, bulkhead.activeCount(), "and every permit came back");
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
