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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContextualExecutor} — verifies §4.4 propagation
 * semantics: the caller's ThreadContext (ECS field carrier) is installed on
 * the runner thread, the runner's prior ThreadContext is restored after the
 * task, and the wrapper works with no APM agent attached (the common case in
 * unit tests).
 */
final class ContextualExecutorTest {

    private ExecutorService backing;

    @BeforeEach
    void setup() {
        ThreadContext.clearMap();
        this.backing = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearMap();
        this.backing.shutdownNow();
    }

    @Test
    @DisplayName("Caller ThreadContext (MDC) is visible on the runner thread")
    void contextualizePropagatesThreadContextAcrossThreadHop() throws Exception {
        ThreadContext.put("trace.id", "trace-abc");
        ThreadContext.put("repository.name", "npm_group");
        final AtomicReference<String> seenTrace = new AtomicReference<>();
        final AtomicReference<String> seenRepo = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            seenTrace.set(ThreadContext.get("trace.id"));
            seenRepo.set(ThreadContext.get("repository.name"));
        }, ContextualExecutor.contextualize(this.backing)).get(5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(seenTrace.get(), Matchers.is("trace-abc"));
        MatcherAssert.assertThat(seenRepo.get(), Matchers.is("npm_group"));
    }

    @Test
    @DisplayName("Runner's ThreadContext is restored after the task completes")
    void contextualizeDoesNotLeakContextIntoRunnerThread() throws Exception {
        // Pre-seed the runner thread's state by running an ordinary task first.
        final AtomicReference<String> runnerNameBefore = new AtomicReference<>();
        this.backing.submit(() -> {
            ThreadContext.put("runner.own", "runner-seed");
            runnerNameBefore.set(Thread.currentThread().getName());
        }).get(5L, TimeUnit.SECONDS);

        // Now submit through the contextualized wrapper with a different MDC.
        ThreadContext.clearMap();
        ThreadContext.put("trace.id", "caller-trace");
        CompletableFuture.runAsync(() -> {
            MatcherAssert.assertThat(
                "caller's MDC visible inside the task",
                ThreadContext.get("trace.id"), Matchers.is("caller-trace")
            );
            MatcherAssert.assertThat(
                "runner's prior MDC is hidden while the task runs",
                ThreadContext.get("runner.own"), Matchers.nullValue()
            );
        }, ContextualExecutor.contextualize(this.backing)).get(5L, TimeUnit.SECONDS);

        // After the task, the runner's prior MDC must be restored.
        final AtomicReference<String> runnerOwnAfter = new AtomicReference<>();
        final AtomicReference<String> traceLeak = new AtomicReference<>();
        this.backing.submit(() -> {
            runnerOwnAfter.set(ThreadContext.get("runner.own"));
            traceLeak.set(ThreadContext.get("trace.id"));
        }).get(5L, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "runner's prior MDC is back",
            runnerOwnAfter.get(), Matchers.is("runner-seed")
        );
        MatcherAssert.assertThat(
            "caller's MDC did not leak into the runner thread",
            traceLeak.get(), Matchers.nullValue()
        );
    }

    @Test
    @DisplayName("Runner's prior ThreadContext is restored even when the task throws")
    void contextualizeRestoresCallerContextEvenIfTaskThrows() throws Exception {
        this.backing.submit(() -> ThreadContext.put("runner.own", "seed")).get(5L, TimeUnit.SECONDS);

        ThreadContext.clearMap();
        ThreadContext.put("trace.id", "throw-trace");

        final CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
            throw new IllegalStateException("boom");
        }, ContextualExecutor.contextualize(this.backing));
        try {
            fut.get(5L, TimeUnit.SECONDS);
            MatcherAssert.assertThat("expected exception", false, Matchers.is(true));
        } catch (final java.util.concurrent.ExecutionException expected) {
            MatcherAssert.assertThat(
                "cause propagated",
                expected.getCause(), Matchers.instanceOf(IllegalStateException.class)
            );
        }

        // Despite the throw, the runner's prior MDC must still be restored.
        final AtomicReference<String> runnerOwnAfter = new AtomicReference<>();
        final AtomicReference<String> traceLeak = new AtomicReference<>();
        this.backing.submit(() -> {
            runnerOwnAfter.set(ThreadContext.get("runner.own"));
            traceLeak.set(ThreadContext.get("trace.id"));
        }).get(5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(runnerOwnAfter.get(), Matchers.is("seed"));
        MatcherAssert.assertThat(
            "caller's MDC did not leak into the runner thread after an exception",
            traceLeak.get(), Matchers.nullValue()
        );
    }

    @Test
    @DisplayName("Wrapper works with no APM agent attached — ElasticApm.currentSpan() is a no-op")
    void contextualizeWorksWithNoApmAgent() throws Exception {
        // No APM agent in the test JVM; ElasticApm.currentSpan() returns a no-op
        // whose activate() Scope is a no-op. The task should run to completion.
        ThreadContext.put("trace.id", "no-agent");
        final AtomicReference<Boolean> ran = new AtomicReference<>(false);
        CompletableFuture.runAsync(
            () -> ran.set(true),
            ContextualExecutor.contextualize(this.backing)
        ).get(5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(ran.get(), Matchers.is(true));
    }

    @Test
    @DisplayName("contextualize(null) throws NullPointerException")
    void contextualizeRejectsNullDelegate() {
        try {
            ContextualExecutor.contextualize(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            MatcherAssert.assertThat(
                "NPE mentions 'delegate'",
                expected.getMessage(), Matchers.containsString("delegate")
            );
        }
    }
}
