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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogCapture;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A future completed by a shared thread (a Lettuce event loop, a Jetty
 * client thread) resumes request code on that thread. The records it logs
 * there must carry the request's {@code trace.id}, and the thread must not
 * keep it: 1,446 unrelated production records of one Lettuce thread carried
 * the trace id of a single earlier request.
 *
 * @since 2.2.14
 */
@Timeout(30)
final class CallerContextTest {

    private static final String LOGGER = "com.auto1.pantera.test.caller-context";

    private static final String TRACE = "trace.id";

    /**
     * Stands for the foreign pool: one thread, so "the same thread later"
     * is deterministic.
     */
    private ExecutorService foreign;

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.foreign = Executors.newSingleThreadExecutor();
        this.capture = LogCapture.of(LOGGER);
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        this.foreign.shutdownNow();
        ThreadContext.clearAll();
    }

    @Test
    void continuationOnForeignThreadLogsUnderCallersTrace() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        ThreadContext.put(TRACE, "aaaa1111aaaa1111");
        final CompletableFuture<Void> done = new CallerContext().resume(answer)
            .thenAccept(val -> CallerContextTest.record("resumed"));
        ThreadContext.clearAll();
        this.foreign.submit(() -> answer.complete("pong")).get(10, TimeUnit.SECONDS);
        done.get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            this.traceOf("resumed"), new IsEqual<>("aaaa1111aaaa1111")
        );
    }

    @Test
    void foreignThreadDoesNotKeepTheTraceOfTheRequestItResumed() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        final Headers headers = Headers.from(
            EcsLoggingSlice.CTX_TRACE_ID_HEADER, "bbbb2222bbbb2222"
        );
        final CompletableFuture<Void> done = new CallerContext().resume(answer)
            .thenAccept(
                val -> {
                    RequestContextHeaders.bindToMdc(headers);
                    CallerContextTest.record("resumed");
                }
            );
        this.foreign.submit(() -> answer.complete("pong")).get(10, TimeUnit.SECONDS);
        done.get(10, TimeUnit.SECONDS);
        this.foreign.submit(() -> CallerContextTest.record("unrelated"))
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the continuation logs under the request it bound",
            this.traceOf("resumed"), new IsEqual<>("bbbb2222bbbb2222")
        );
        MatcherAssert.assertThat(
            "a later task of the same thread has no trace id of that request",
            this.traceOf("unrelated"), new IsEqual<>(null)
        );
    }

    @Test
    void foreignThreadGetsItsOwnContextBack() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        ThreadContext.put(TRACE, "cccc3333cccc3333");
        final CompletableFuture<String> resumed = new CallerContext().resume(answer);
        ThreadContext.clearAll();
        final String after = this.foreign.submit(
            () -> {
                ThreadContext.put(TRACE, "own-context");
                answer.complete("pong");
                return ThreadContext.get(TRACE);
            }
        ).get(10, TimeUnit.SECONDS);
        resumed.get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(after, new IsEqual<>("own-context"));
    }

    @Test
    void failureIsRelayedUnderCallersTrace() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        ThreadContext.put(TRACE, "dddd4444dddd4444");
        final CompletableFuture<String> done = new CallerContext().resume(answer)
            .exceptionally(
                err -> {
                    CallerContextTest.record("failed");
                    return err.getMessage();
                }
            );
        ThreadContext.clearAll();
        this.foreign.submit(
            () -> answer.completeExceptionally(new IllegalStateException("valkey down"))
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the cause reaches the continuation unwrapped",
            done.get(10, TimeUnit.SECONDS), new IsEqual<>("valkey down")
        );
        MatcherAssert.assertThat(
            "the failure record carries the request's trace id",
            this.traceOf("failed"), new IsEqual<>("dddd4444dddd4444")
        );
    }

    private static void record(final String action) {
        EcsLogger.warn(LOGGER)
            .message("record")
            .eventCategory("process")
            .eventAction(action)
            .log();
    }

    private Object traceOf(final String action) {
        final Map<String, Object> event = this.capture.action(action).get(0);
        return event.get(TRACE);
    }
}
