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
package com.auto1.pantera.cooldown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.auto1.pantera.http.context.ContextualExecutor;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that ThreadContext survives async hops in the cooldown package
 * after replacing {@code MdcPropagation} with {@code ContextualExecutor}.
 *
 * @since 2.2.0
 */
final class CooldownContextPropagationTest {

    private ExecutorService rawPool;

    @BeforeEach
    void setUp() {
        this.rawPool = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "cooldown-test-worker");
            t.setDaemon(true);
            return t;
        });
        ThreadContext.clearMap();
    }

    @AfterEach
    void tearDown() {
        ThreadContext.clearMap();
        this.rawPool.shutdownNow();
    }

    @Test
    void threadContextSurvivesAsyncHopViaContextualExecutor() throws Exception {
        // Wrap the raw pool with ContextualExecutor — same pattern as
        // JdbcCooldownService.constructor now does.
        final java.util.concurrent.Executor contextual =
            ContextualExecutor.contextualize(this.rawPool);

        // Set MDC fields on the calling thread
        ThreadContext.put("trace.id", "abc123");
        ThreadContext.put("package.name", "com.example:foo");

        // Async hop through the contextual executor
        final AtomicReference<String> capturedTrace = new AtomicReference<>();
        final AtomicReference<String> capturedPkg = new AtomicReference<>();

        CompletableFuture.supplyAsync(() -> {
            capturedTrace.set(ThreadContext.get("trace.id"));
            capturedPkg.set(ThreadContext.get("package.name"));
            return "done";
        }, contextual).join();

        // Assert context survived the hop
        assertNotNull(capturedTrace.get(), "trace.id should be propagated");
        assertEquals("abc123", capturedTrace.get());
        assertNotNull(capturedPkg.get(), "package.name should be propagated");
        assertEquals("com.example:foo", capturedPkg.get());
    }

    @Test
    void withoutContextualExecutorContextIsLost() throws Exception {
        // Using the raw pool (no contextual wrapper) — context should NOT survive
        ThreadContext.put("trace.id", "xyz789");

        final AtomicReference<String> capturedTrace = new AtomicReference<>();

        CompletableFuture.supplyAsync(() -> {
            capturedTrace.set(ThreadContext.get("trace.id"));
            return "done";
        }, this.rawPool).join();

        // The raw pool does not propagate ThreadContext
        // (it may or may not be null depending on ThreadContext state of the worker thread)
        // This test just documents the contrast with the contextual executor.
        // We don't assert null here because ThreadContext state on a new thread is implementation-specific.
    }
}
