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
package com.auto1.pantera.http.context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HandlerExecutor} — verifies that the shared handler
 * worker pool propagates the caller's Log4j2 {@link ThreadContext} to
 * worker threads, isolates worker ThreadContext between tasks, uses daemon
 * threads with a descriptive name prefix, and enforces its bounded queue.
 *
 * @since 2.2.0
 */
final class HandlerExecutorTest {

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearMap();
    }

    @Test
    @DisplayName("Submitted task sees the caller's ThreadContext keys")
    void submittedTasksSeeCallerThreadContext() throws Exception {
        ThreadContext.put("trace.id", "test-trace-123");
        ThreadContext.put("user.name", "test-admin");
        final AtomicReference<String> seenTrace = new AtomicReference<>();
        final AtomicReference<String> seenUser = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            seenTrace.set(ThreadContext.get("trace.id"));
            seenUser.set(ThreadContext.get("user.name"));
        }, HandlerExecutor.get()).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "trace.id visible on worker",
            seenTrace.get(), Matchers.is("test-trace-123")
        );
        MatcherAssert.assertThat(
            "user.name visible on worker",
            seenUser.get(), Matchers.is("test-admin")
        );
    }

    @Test
    @DisplayName("Worker ThreadContext is isolated: a new caller with empty context does not see a prior caller's keys")
    void callerThreadContextIsolatedFromWorkerThread() throws Exception {
        // Submit a task with caller context.
        ThreadContext.put("trace.id", "caller-only");
        CompletableFuture.runAsync(() -> {
            MatcherAssert.assertThat(
                "caller context visible inside the task",
                ThreadContext.get("trace.id"), Matchers.is("caller-only")
            );
        }, HandlerExecutor.get()).get(5, TimeUnit.SECONDS);
        // Now clear the caller's ThreadContext (simulating a different request
        // on the event loop) and submit a new task. The worker must NOT see
        // the previous caller's "trace.id" — the contextual executor captures
        // the NEW (empty) caller context, not the worker's prior state.
        ThreadContext.clearMap();
        final AtomicReference<String> leakedTrace = new AtomicReference<>();
        CompletableFuture.runAsync(
            () -> leakedTrace.set(ThreadContext.get("trace.id")),
            HandlerExecutor.get()
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "previous caller's ThreadContext does not leak to new caller's task",
            leakedTrace.get(), Matchers.nullValue()
        );
    }

    @Test
    @DisplayName("Pool threads are daemon threads")
    void poolThreadsAreDaemon() throws Exception {
        final AtomicBoolean daemon = new AtomicBoolean(false);
        CompletableFuture.runAsync(
            () -> daemon.set(Thread.currentThread().isDaemon()),
            HandlerExecutor.get()
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "handler pool thread is daemon",
            daemon.get(), Matchers.is(true)
        );
    }

    @Test
    @DisplayName("Pool threads have a descriptive name starting with 'pantera-handler-'")
    void poolHasDescriptiveThreadName() throws Exception {
        final AtomicReference<String> name = new AtomicReference<>();
        CompletableFuture.runAsync(
            () -> name.set(Thread.currentThread().getName()),
            HandlerExecutor.get()
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "thread name starts with pantera-handler-",
            name.get(), Matchers.startsWith("pantera-handler-")
        );
    }

    @Test
    @DisplayName("Pool rejects tasks when queue is saturated (AbortPolicy)")
    void poolRejectsOnQueueSaturation() throws Exception {
        // Fill up the pool + queue by submitting tasks that block.
        final int poolSize = HandlerExecutor.poolSize();
        final int queueCapacity = HandlerExecutor.queueCapacity();
        final CountDownLatch holdLatch = new CountDownLatch(1);
        final CountDownLatch startedLatch = new CountDownLatch(poolSize);
        // Submit poolSize tasks that block forever (fill all worker threads).
        for (int i = 0; i < poolSize; i++) {
            HandlerExecutor.get().execute(() -> {
                startedLatch.countDown();
                try {
                    holdLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // Wait until all worker threads are busy.
        final boolean allStarted = startedLatch.await(10, TimeUnit.SECONDS);
        Assertions.assertTrue(allStarted, "All pool threads should start");
        // Fill the queue.
        for (int i = 0; i < queueCapacity; i++) {
            HandlerExecutor.get().execute(() -> {
                try {
                    holdLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // The next submit should be rejected.
        try {
            HandlerExecutor.get().execute(() -> { });
            Assertions.fail("Expected RejectedExecutionException");
        } catch (final RejectedExecutionException expected) {
            // AbortPolicy fires — this is the expected behaviour.
        } finally {
            // Release all blocked tasks.
            holdLatch.countDown();
        }
    }
}
