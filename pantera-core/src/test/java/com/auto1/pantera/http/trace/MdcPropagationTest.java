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
package com.auto1.pantera.http.trace;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link MdcPropagation}.
 * @since 2.1.0
 */
final class MdcPropagationTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        MDC.clear();
        this.pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        this.pool.shutdownNow();
    }

    @Test
    void propagatesMdcToWorkerThread() throws Exception {
        MDC.put("trace.id", "abc123def456abc1");
        MDC.put("span.id", "1234567890abcdef");
        final AtomicReference<String> captured = new AtomicReference<>();
        final Future<String> future = this.pool.submit(
            MdcPropagation.withMdc(() -> {
                captured.set(MDC.get("span.id"));
                return MDC.get("trace.id");
            })
        );
        MatcherAssert.assertThat(future.get(), Matchers.equalTo("abc123def456abc1"));
        MatcherAssert.assertThat(captured.get(), Matchers.equalTo("1234567890abcdef"));
    }

    @Test
    void restoresPriorMdcAfterCallable() throws Exception {
        MDC.put("trace.id", "caller-trace");
        final AtomicReference<String> before = new AtomicReference<>();
        final AtomicReference<String> after = new AtomicReference<>();
        final Future<Void> future = this.pool.submit(() -> {
            MDC.put("trace.id", "worker-prior");
            before.set(MDC.get("trace.id"));
            MdcPropagation.withMdc(() -> {
                return MDC.get("trace.id");
            }).call();
            after.set(MDC.get("trace.id"));
            return null;
        });
        future.get();
        MatcherAssert.assertThat(before.get(), Matchers.equalTo("worker-prior"));
        MatcherAssert.assertThat(after.get(), Matchers.equalTo("worker-prior"));
    }

    @Test
    void handlesNullCapturedMdc() throws Exception {
        MDC.clear();
        final Future<String> future = this.pool.submit(
            MdcPropagation.withMdc(() -> MDC.get("trace.id"))
        );
        MatcherAssert.assertThat(future.get(), Matchers.nullValue());
    }

    @Test
    void runnableVariantPropagatesMdc() throws Exception {
        MDC.put("trace.id", "runnable-trace");
        final AtomicReference<String> captured = new AtomicReference<>();
        final Future<?> future = this.pool.submit(
            MdcPropagation.withMdc(() -> captured.set(MDC.get("trace.id")))
        );
        future.get();
        MatcherAssert.assertThat(captured.get(), Matchers.equalTo("runnable-trace"));
    }
}
