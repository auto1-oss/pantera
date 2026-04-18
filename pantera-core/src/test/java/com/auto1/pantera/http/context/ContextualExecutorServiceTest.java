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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContextualExecutorService} — the WI-post-03a wrapper that
 * contextualises <em>every</em> task-submission method, closing the gap left
 * by {@link ContextualExecutor} (which only decorates the bare
 * {@link java.util.concurrent.Executor} interface).
 *
 * <p>Each test asserts one of:
 * <ul>
 *   <li>a specific submit path propagates the caller's Log4j2
 *       {@link ThreadContext} to the runner thread, or</li>
 *   <li>the runner's prior ThreadContext is restored after the task (no leak),
 *       even when the task throws, or</li>
 *   <li>lifecycle methods delegate to the underlying pool.</li>
 * </ul>
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals",
    "PMD.JUnitAssertionsShouldIncludeMessage"})
final class ContextualExecutorServiceTest {

    /**
     * Raw pool the wrapper decorates. Recreated per test.
     */
    private ExecutorService backing;

    /**
     * The wrapper under test.
     */
    private ContextualExecutorService wrapper;

    @BeforeEach
    void setup() {
        ThreadContext.clearMap();
        this.backing = Executors.newFixedThreadPool(2);
        this.wrapper = ContextualExecutorService.wrap(this.backing);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ThreadContext.clearMap();
        this.wrapper.shutdownNow();
        this.backing.shutdownNow();
        this.backing.awaitTermination(5L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("execute(Runnable) propagates caller ThreadContext to runner thread")
    void wrappedExecutePropagatesThreadContext() throws Exception {
        ThreadContext.put("trace.id", "exec-trace");
        ThreadContext.put("repository.name", "npm_group");
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> seenTrace = new AtomicReference<>();
        final AtomicReference<String> seenRepo = new AtomicReference<>();
        this.wrapper.execute(() -> {
            try {
                seenTrace.set(ThreadContext.get("trace.id"));
                seenRepo.set(ThreadContext.get("repository.name"));
            } finally {
                done.countDown();
            }
        });
        MatcherAssert.assertThat(
            done.await(5L, TimeUnit.SECONDS), Matchers.is(true)
        );
        MatcherAssert.assertThat(seenTrace.get(), Matchers.is("exec-trace"));
        MatcherAssert.assertThat(seenRepo.get(), Matchers.is("npm_group"));
    }

    @Test
    @DisplayName("submit(Callable) propagates caller ThreadContext to runner thread "
        + "— closes the bypass flagged by the Wave-3 review")
    void wrappedSubmitCallablePropagatesThreadContext() throws Exception {
        ThreadContext.put("trace.id", "submit-callable");
        ThreadContext.put("repository.name", "maven_group");
        final Future<String> fut = this.wrapper.submit((Callable<String>) () -> {
            final String trace = ThreadContext.get("trace.id");
            final String repo = ThreadContext.get("repository.name");
            return trace + "|" + repo;
        });
        MatcherAssert.assertThat(
            fut.get(5L, TimeUnit.SECONDS),
            Matchers.is("submit-callable|maven_group")
        );
    }

    @Test
    @DisplayName("submit(Runnable) propagates caller ThreadContext to runner thread")
    void wrappedSubmitRunnablePropagatesThreadContext() throws Exception {
        ThreadContext.put("trace.id", "submit-runnable");
        final AtomicReference<String> seen = new AtomicReference<>();
        final Future<?> fut = this.wrapper.submit((Runnable) () ->
            seen.set(ThreadContext.get("trace.id"))
        );
        fut.get(5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(seen.get(), Matchers.is("submit-runnable"));
    }

    @Test
    @DisplayName("submit(Runnable, result) propagates caller ThreadContext and "
        + "returns the provided result")
    void wrappedSubmitRunnableResultPropagatesThreadContext() throws Exception {
        ThreadContext.put("trace.id", "submit-runnable-result");
        final AtomicReference<String> seen = new AtomicReference<>();
        final Future<String> fut = this.wrapper.submit(
            () -> seen.set(ThreadContext.get("trace.id")),
            "ok"
        );
        MatcherAssert.assertThat(fut.get(5L, TimeUnit.SECONDS), Matchers.is("ok"));
        MatcherAssert.assertThat(seen.get(), Matchers.is("submit-runnable-result"));
    }

    @Test
    @DisplayName("invokeAll propagates caller ThreadContext to every task")
    void wrappedInvokeAllPropagatesThreadContextToEveryTask() throws Exception {
        ThreadContext.put("trace.id", "invoke-all");
        final List<Callable<String>> tasks = Arrays.asList(
            () -> ThreadContext.get("trace.id") + ":a",
            () -> ThreadContext.get("trace.id") + ":b",
            () -> ThreadContext.get("trace.id") + ":c"
        );
        final List<Future<String>> futures = this.wrapper.invokeAll(tasks);
        final List<String> results = new ArrayList<>(futures.size());
        for (final Future<String> f : futures) {
            results.add(f.get(5L, TimeUnit.SECONDS));
        }
        MatcherAssert.assertThat(
            results,
            Matchers.containsInAnyOrder(
                "invoke-all:a", "invoke-all:b", "invoke-all:c"
            )
        );
    }

    @Test
    @DisplayName("invokeAll(..., timeout) propagates caller ThreadContext to every task")
    void wrappedInvokeAllTimeoutPropagatesThreadContextToEveryTask() throws Exception {
        ThreadContext.put("trace.id", "invoke-all-to");
        final List<Callable<String>> tasks = Arrays.asList(
            () -> ThreadContext.get("trace.id") + ":x",
            () -> ThreadContext.get("trace.id") + ":y"
        );
        final List<Future<String>> futures = this.wrapper.invokeAll(
            tasks, 5L, TimeUnit.SECONDS
        );
        final List<String> results = new ArrayList<>(futures.size());
        for (final Future<String> f : futures) {
            results.add(f.get(5L, TimeUnit.SECONDS));
        }
        MatcherAssert.assertThat(
            results,
            Matchers.containsInAnyOrder("invoke-all-to:x", "invoke-all-to:y")
        );
    }

    @Test
    @DisplayName("invokeAny propagates caller ThreadContext to every task")
    void wrappedInvokeAnyPropagatesThreadContextToEveryTask() throws Exception {
        ThreadContext.put("trace.id", "invoke-any");
        final List<Callable<String>> tasks = Arrays.asList(
            () -> ThreadContext.get("trace.id") + ":first",
            () -> ThreadContext.get("trace.id") + ":second"
        );
        final String result = this.wrapper.invokeAny(tasks);
        MatcherAssert.assertThat(
            result,
            Matchers.anyOf(
                Matchers.is("invoke-any:first"),
                Matchers.is("invoke-any:second")
            )
        );
    }

    @Test
    @DisplayName("invokeAny(..., timeout) propagates caller ThreadContext to every task")
    void wrappedInvokeAnyTimeoutPropagatesThreadContextToEveryTask() throws Exception {
        ThreadContext.put("trace.id", "invoke-any-to");
        final List<Callable<String>> tasks = Arrays.asList(
            () -> ThreadContext.get("trace.id") + ":only"
        );
        final String result = this.wrapper.invokeAny(tasks, 5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(result, Matchers.is("invoke-any-to:only"));
    }

    @Test
    @DisplayName("shutdown() delegates to underlying pool")
    void shutdownDelegatesToUnderlyingPool() throws Exception {
        MatcherAssert.assertThat(this.wrapper.isShutdown(), Matchers.is(false));
        MatcherAssert.assertThat(this.backing.isShutdown(), Matchers.is(false));
        this.wrapper.shutdown();
        MatcherAssert.assertThat(this.backing.isShutdown(), Matchers.is(true));
        MatcherAssert.assertThat(this.wrapper.isShutdown(), Matchers.is(true));
        MatcherAssert.assertThat(
            this.wrapper.awaitTermination(5L, TimeUnit.SECONDS),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(this.wrapper.isTerminated(), Matchers.is(true));
        MatcherAssert.assertThat(this.backing.isTerminated(), Matchers.is(true));
    }

    @Test
    @DisplayName("shutdownNow() delegates to underlying pool and returns pending tasks")
    void shutdownNowDelegatesAndReturnsPendingTasks() throws Exception {
        // Saturate the 2-thread pool with a blocking task so a following
        // submit queues instead of running.
        final CountDownLatch blockStart = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            this.wrapper.submit(() -> {
                blockStart.countDown();
                try {
                    release.await(5L, TimeUnit.SECONDS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        MatcherAssert.assertThat(
            blockStart.await(5L, TimeUnit.SECONDS), Matchers.is(true)
        );
        // Queue a third task — it should NOT start before shutdownNow.
        this.wrapper.submit(() -> {
            // Never runs — shutdownNow should return it as pending.
        });
        final List<Runnable> pending = this.wrapper.shutdownNow();
        release.countDown();
        MatcherAssert.assertThat(
            "shutdownNow returns at least the queued task",
            pending.size(), Matchers.greaterThanOrEqualTo(1)
        );
        MatcherAssert.assertThat(this.backing.isShutdown(), Matchers.is(true));
    }

    @Test
    @DisplayName("wrap(null) throws NullPointerException on the delegate parameter")
    void wrapRejectsNullDelegate() {
        try {
            ContextualExecutorService.wrap(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(
                "NPE mentions 'delegate'",
                expected.getMessage(), Matchers.containsString("delegate")
            );
        }
    }

    @Test
    @DisplayName("Runner thread's prior ThreadContext is restored after the task — "
        + "no leak across invocations (submit(Callable) path)")
    void contextRestoredOnRunnerThreadAfterTask() throws Exception {
        // Pin to a single-thread pool so we can prove restore on the *same* runner.
        final ExecutorService single = Executors.newSingleThreadExecutor();
        final ContextualExecutorService pin = ContextualExecutorService.wrap(single);
        try {
            // Seed the runner's own ThreadContext via the RAW pool — bypassing the
            // wrapper so the seed persists on the runner thread. If we seeded
            // through the wrapper, the post-task restore would wipe it.
            single.submit(() -> ThreadContext.put("runner.own", "runner-seed"))
                .get(5L, TimeUnit.SECONDS);

            // Submit with a different caller context through the wrapper.
            ThreadContext.clearMap();
            ThreadContext.put("trace.id", "caller-trace");
            final String seenCaller = pin.submit((Callable<String>) () -> {
                MatcherAssert.assertThat(
                    "runner's own MDC is hidden while the task runs",
                    ThreadContext.get("runner.own"), Matchers.nullValue()
                );
                return ThreadContext.get("trace.id");
            }).get(5L, TimeUnit.SECONDS);
            MatcherAssert.assertThat(seenCaller, Matchers.is("caller-trace"));

            // Next task on the RAW pool: observe the runner's prior MDC is back,
            // and the caller's MDC did NOT leak onto the runner.
            ThreadContext.clearMap();
            final AtomicReference<String> runnerOwnAfter = new AtomicReference<>();
            final AtomicReference<String> traceLeak = new AtomicReference<>();
            single.submit(() -> {
                runnerOwnAfter.set(ThreadContext.get("runner.own"));
                traceLeak.set(ThreadContext.get("trace.id"));
            }).get(5L, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "runner's prior MDC was restored after the wrapped task",
                runnerOwnAfter.get(), Matchers.is("runner-seed")
            );
            MatcherAssert.assertThat(
                "caller's MDC did not leak onto runner thread",
                traceLeak.get(), Matchers.nullValue()
            );
        } finally {
            pin.shutdownNow();
            single.shutdownNow();
            single.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Runner's prior ThreadContext is restored even when submitted "
        + "task throws (submit(Callable) path)")
    void contextRestoredEvenIfTaskThrows() throws Exception {
        final ExecutorService single = Executors.newSingleThreadExecutor();
        final ContextualExecutorService pin = ContextualExecutorService.wrap(single);
        try {
            // Seed the runner via the RAW pool so the seed survives.
            single.submit(() -> ThreadContext.put("runner.own", "seed"))
                .get(5L, TimeUnit.SECONDS);

            ThreadContext.clearMap();
            ThreadContext.put("trace.id", "throw-trace");
            final Future<String> boom = pin.submit((Callable<String>) () -> {
                MatcherAssert.assertThat(
                    ThreadContext.get("trace.id"), Matchers.is("throw-trace")
                );
                throw new IllegalStateException("boom");
            });
            final AtomicBoolean threw = new AtomicBoolean(false);
            try {
                boom.get(5L, TimeUnit.SECONDS);
            } catch (final java.util.concurrent.ExecutionException expected) {
                threw.set(
                    expected.getCause() instanceof IllegalStateException
                );
            }
            MatcherAssert.assertThat(
                "callable propagated IllegalStateException",
                threw.get(), Matchers.is(true)
            );

            // Runner's MDC must be restored; caller's MDC must NOT leak.
            ThreadContext.clearMap();
            final AtomicReference<String> runnerOwnAfter = new AtomicReference<>();
            final AtomicReference<String> traceLeak = new AtomicReference<>();
            single.submit(() -> {
                runnerOwnAfter.set(ThreadContext.get("runner.own"));
                traceLeak.set(ThreadContext.get("trace.id"));
            }).get(5L, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "runner's prior MDC was restored after the throwing task",
                runnerOwnAfter.get(), Matchers.is("seed")
            );
            MatcherAssert.assertThat(
                "caller MDC did not leak after throw",
                traceLeak.get(), Matchers.nullValue()
            );
        } finally {
            pin.shutdownNow();
            single.shutdownNow();
            single.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }
}
