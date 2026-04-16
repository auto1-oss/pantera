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

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link MdcPropagation}. Deprecation warnings are suppressed here
 * because WI-03 marked {@code MdcPropagation} {@code @Deprecated(forRemoval=true)}
 * — this test stays green for as long as the class ships, ensuring behaviour
 * does not regress before WI-08 removes it.
 * @since 2.1.0
 */
@SuppressWarnings({"deprecation", "removal"})
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

    @Test
    void capturedMdcRestoredInRunWith() {
        MDC.put("trace.id", "test-abc");
        final Map<String, String> snapshot = MdcPropagation.capture();
        MDC.clear();

        final AtomicReference<String> seen = new AtomicReference<>();
        MdcPropagation.runWith(
            snapshot, () -> seen.set(MDC.get("trace.id"))
        );

        MatcherAssert.assertThat(seen.get(), Matchers.equalTo("test-abc"));
        // prior state restored (was empty after clear)
        MatcherAssert.assertThat(MDC.get("trace.id"), Matchers.nullValue());
    }

    @Test
    void captureReturnsNullOnEmptyMdc() {
        MDC.clear();
        MatcherAssert.assertThat(MdcPropagation.capture(), Matchers.nullValue());
    }

    @Test
    void runWithNullSnapshotIsNoOpForMdc() {
        MDC.put("trace.id", "prior");
        final AtomicReference<String> seen = new AtomicReference<>();
        MdcPropagation.runWith(null, () -> seen.set(MDC.get("trace.id")));
        MatcherAssert.assertThat(seen.get(), Matchers.equalTo("prior"));
        MatcherAssert.assertThat(MDC.get("trace.id"), Matchers.equalTo("prior"));
    }

    @Test
    void runWithRestoresPriorMdcAfterException() {
        MDC.put("trace.id", "prior");
        final Map<String, String> snap = Map.of("trace.id", "snap");
        final AtomicReference<String> inside = new AtomicReference<>();
        try {
            MdcPropagation.runWith(snap, () -> {
                inside.set(MDC.get("trace.id"));
                throw new IllegalStateException("boom");
            });
        } catch (final IllegalStateException ignore) {
            // expected
        }
        MatcherAssert.assertThat(inside.get(), Matchers.equalTo("snap"));
        // prior restored even though action threw
        MatcherAssert.assertThat(MDC.get("trace.id"), Matchers.equalTo("prior"));
    }

    @Test
    void consumerVariantPropagatesMdc() throws Exception {
        MDC.put("trace.id", "consumer-trace");
        final AtomicReference<String> seen = new AtomicReference<>();
        final Consumer<String> wrapped = MdcPropagation.withMdcConsumer(
            arg -> seen.set(MDC.get("trace.id") + ":" + arg)
        );
        MDC.clear();
        // Run on worker thread so there is no MDC to start with
        final Future<?> future = this.pool.submit(() -> wrapped.accept("x"));
        future.get();
        MatcherAssert.assertThat(seen.get(), Matchers.equalTo("consumer-trace:x"));
    }
}
