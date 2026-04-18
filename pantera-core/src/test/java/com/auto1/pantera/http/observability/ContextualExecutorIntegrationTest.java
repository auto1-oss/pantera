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
package com.auto1.pantera.http.observability;

import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.resilience.SingleFlight;
import java.time.Duration;
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
 * End-to-end propagation test — verifies that wiring
 * {@link ContextualExecutor#contextualize(java.util.concurrent.Executor)}
 * into a {@link SingleFlight} (the WI-03 executor-wrapping points in
 * {@code MavenGroupSlice}, {@code BaseCachedProxySlice},
 * {@code CachedNpmProxySlice}) means callers no longer need to wrap each
 * continuation by hand: the executor itself snapshots the caller's
 * {@link ThreadContext} and APM span and installs them on the runner thread
 * for the duration of the task.
 */
final class ContextualExecutorIntegrationTest {

    private ExecutorService backing;

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
        this.backing = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearMap();
        this.backing.shutdownNow();
    }

    @Test
    @DisplayName("SingleFlight wrapped with ContextualExecutor propagates ThreadContext")
    void singleFlightPropagatesContext() throws Exception {
        final SingleFlight<String, String> sf = new SingleFlight<>(
            Duration.ofSeconds(5),
            16,
            ContextualExecutor.contextualize(this.backing)
        );

        ThreadContext.put("trace.id", "wi03-trace");
        ThreadContext.put("repository.name", "npm_proxy");
        final AtomicReference<String> seenTrace = new AtomicReference<>();
        final AtomicReference<String> seenRepo = new AtomicReference<>();

        // Submit via SingleFlight. The loader itself may run synchronously on
        // the caller; the follower thread that's dispatched for completion
        // is the one wrapped by ContextualExecutor. We assert the completion
        // callback sees the caller's context.
        final CompletableFuture<String> fut = sf.load("k1", () ->
            CompletableFuture.supplyAsync(() -> "value", this.backing)
        );
        fut.thenApplyAsync(v -> {
            seenTrace.set(ThreadContext.get("trace.id"));
            seenRepo.set(ThreadContext.get("repository.name"));
            return v;
        }, ContextualExecutor.contextualize(this.backing)).get(5L, TimeUnit.SECONDS);

        MatcherAssert.assertThat(seenTrace.get(), Matchers.is("wi03-trace"));
        MatcherAssert.assertThat(seenRepo.get(), Matchers.is("npm_proxy"));
    }

    @Test
    @DisplayName("Runner thread's prior ThreadContext is restored after the task")
    void runnerThreadContextRestored() throws Exception {
        // Seed the runner thread with its own prior context.
        this.backing.submit(() -> ThreadContext.put("pre", "runner"))
            .get(5L, TimeUnit.SECONDS);

        // Submit via the contextualised executor with a different caller ctx.
        ThreadContext.clearMap();
        ThreadContext.put("trace.id", "fresh");
        CompletableFuture.runAsync(() -> {
            MatcherAssert.assertThat(
                ThreadContext.get("trace.id"), Matchers.is("fresh")
            );
            MatcherAssert.assertThat(
                "runner's prior ctx must be hidden during task",
                ThreadContext.get("pre"), Matchers.nullValue()
            );
        }, ContextualExecutor.contextualize(this.backing)).get(5L, TimeUnit.SECONDS);

        // After the task, the runner's prior ctx must be back.
        final AtomicReference<String> restored = new AtomicReference<>();
        this.backing.submit(() -> restored.set(ThreadContext.get("pre")))
            .get(5L, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "runner's prior ctx restored after contextualised task",
            restored.get(), Matchers.is("runner")
        );
    }

    @Test
    @DisplayName("Chained thenApplyAsync/thenComposeAsync see caller context via ContextualExecutor")
    void chainedStagesSeeContextWithoutManualMdc() throws Exception {
        ThreadContext.put("trace.id", "chain-1");
        ThreadContext.put("user.name", "alice");
        final AtomicReference<String> stage2Trace = new AtomicReference<>();
        final AtomicReference<String> stage3User = new AtomicReference<>();

        CompletableFuture
            .supplyAsync(() -> 1, ContextualExecutor.contextualize(this.backing))
            .thenApplyAsync(v -> {
                stage2Trace.set(ThreadContext.get("trace.id"));
                return v + 1;
            }, ContextualExecutor.contextualize(this.backing))
            .thenComposeAsync(v -> {
                stage3User.set(ThreadContext.get("user.name"));
                return CompletableFuture.completedFuture(v);
            }, ContextualExecutor.contextualize(this.backing))
            .get(5L, TimeUnit.SECONDS);

        MatcherAssert.assertThat(stage2Trace.get(), Matchers.is("chain-1"));
        MatcherAssert.assertThat(stage3User.get(), Matchers.is("alice"));
    }
}
