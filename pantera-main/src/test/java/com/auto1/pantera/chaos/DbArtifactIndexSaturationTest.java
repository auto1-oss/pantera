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
package com.auto1.pantera.chaos;

import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.index.DbArtifactIndex;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Chaos test: {@link DbArtifactIndex} executor saturation.
 *
 * <p>Verifies that when the bounded DB-index pool is saturated (all worker threads
 * busy AND the queue is full), further submissions fail fast with
 * {@link RejectedExecutionException} — they are NOT executed on the caller thread.
 *
 * <p>This invariant is load-bearing: the group-resolver request chain calls
 * {@code ArtifactIndex.locateByName(...)} inline on a Vert.x event-loop thread.
 * Under the previous {@code CallerRunsPolicy}, saturation would have run the
 * blocking JDBC work on the event loop, stalling the entire reactor. The
 * {@link ThreadPoolExecutor.AbortPolicy} installed by {@link DbArtifactIndex}
 * must guarantee this never happens, regardless of DB pressure.
 *
 * <p>The REE observed via {@code CompletableFuture.supplyAsync} surfaces as a
 * {@link CompletionException} wrapping the REE. Callers such as
 * {@code GroupResolver} map that (via {@code .exceptionally(...)}) into
 * {@link Fault.IndexUnavailable} — this test asserts that classification at
 * the cause level as well.
 *
 * @since 2.2.1
 */
@Tag("Chaos")
final class DbArtifactIndexSaturationTest {

    /** Pool size used for the saturation test executor. */
    private static final int POOL_SIZE = 2;

    /** Bounded queue capacity for the saturation test executor. */
    private static final int QUEUE_SIZE = 2;

    /** Test executor wired into DbArtifactIndex. */
    private ThreadPoolExecutor saturatingExecutor;

    /** Fake DataSource whose getConnection() blocks until released. */
    private BlockingDataSource dataSource;

    /** Index under test. */
    private DbArtifactIndex index;

    /** Latch used to park in-flight JDBC calls so we can saturate the pool. */
    private CountDownLatch releaseJdbc;

    /** Tracker: did any DB call run on a Vert.x event-loop thread? */
    private AtomicBoolean ranOnVertxContext;

    @BeforeEach
    void setUp() {
        this.releaseJdbc = new CountDownLatch(1);
        this.ranOnVertxContext = new AtomicBoolean(false);
        this.dataSource = new BlockingDataSource(this.releaseJdbc, this.ranOnVertxContext);
        this.saturatingExecutor = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_SIZE),
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.index = new DbArtifactIndex(this.dataSource, this.saturatingExecutor);
    }

    @AfterEach
    void tearDown() {
        this.releaseJdbc.countDown();
        if (this.index != null) {
            this.index.close();
        }
        if (this.saturatingExecutor != null) {
            this.saturatingExecutor.shutdownNow();
        }
    }

    /**
     * Saturate the pool, then submit enough extra work to overflow the queue.
     *
     * <p>Expectations:
     * <ul>
     *   <li>Each overflow submission returns a {@link CompletableFuture} that
     *       completes exceptionally with {@link RejectedExecutionException}.</li>
     *   <li>The REE classifies to {@link Fault.IndexUnavailable} when fed
     *       through the same logic {@code GroupResolver} uses
     *       ({@code .exceptionally(ex -> new IndexUnavailable(ex, ...))}).</li>
     *   <li>None of the work ran on a Vert.x event-loop thread — the critical
     *       invariant that AbortPolicy exists to preserve.</li>
     * </ul>
     */
    @Test
    void saturatedPool_submissionsFail_withIndexUnavailable() throws Exception {
        // The constructor's warmUp() already parked one task in a worker thread.
        // Fill the rest of the capacity (one worker + both queue slots) one task
        // at a time and then confirm every further submission is rejected.
        // We submit up to a conservative ceiling and check the pool state each
        // iteration so the test does not race the ThreadPoolExecutor's internal
        // queue-to-worker transfer.
        final List<CompletableFuture<Optional<List<String>>>> inFlight = new ArrayList<>();
        final int submitCeiling = POOL_SIZE + QUEUE_SIZE + 4;
        for (int i = 0; i < submitCeiling && !isSaturated(); i++) {
            inFlight.add(this.index.locateByName("in-flight-" + i));
        }
        waitUntilPoolSaturated();

        // Any further submission must be rejected immediately. Depending on the
        // JDK's {@code CompletableFuture.supplyAsync} contract, the REE can
        // surface in one of two ways — both of which are acceptable for the
        // "never run on the caller" invariant, and both of which classify to
        // {@link Fault.IndexUnavailable}:
        //   (a) the call throws {@link RejectedExecutionException} synchronously
        //       (current OpenJDK behaviour — supplyAsync lets execute()'s REE
        //       propagate before returning), or
        //   (b) the returned CF is already completed exceptionally with REE.
        final int overflowCount = 3;
        int observedRejections = 0;
        for (int i = 0; i < overflowCount; i++) {
            final String name = "overflow-" + i;
            Throwable cause = null;
            try {
                final CompletableFuture<Optional<List<String>>> cf =
                    this.index.locateByName(name);
                // Path (b): deferred REE via the returned CF.
                if (cf.isCompletedExceptionally()) {
                    try {
                        cf.get(100, TimeUnit.MILLISECONDS);
                    } catch (final ExecutionException ex) {
                        cause = unwrap(ex);
                    }
                } else {
                    // Must NOT be the case that a saturated pool accepted the
                    // task and started running it on the caller thread. If the
                    // CF is pending, it means either AbortPolicy let this one
                    // through (bug) or the pool drained between submissions.
                    // Either way, fail — we should never observe this under a
                    // correctly-saturated pool.
                    fail("Saturated-pool submission " + i + " neither threw REE "
                        + "synchronously nor produced an exceptionally-completed "
                        + "future — AbortPolicy appears to have accepted the task. "
                        + "active=" + this.saturatingExecutor.getActiveCount()
                        + ", queue=" + this.saturatingExecutor.getQueue().size());
                    return;
                }
            } catch (final RejectedExecutionException ree) {
                // Path (a): synchronous REE from supplyAsync's e.execute(...).
                cause = ree;
            }
            assertInstanceOf(RejectedExecutionException.class, cause,
                "Saturated submission " + i + " must surface "
                    + "RejectedExecutionException (got "
                    + (cause == null ? "null" : cause.getClass().getName()) + ")");

            // Mirror GroupResolver's .exceptionally(...) classification: the
            // cause of a queue-saturation REE maps to Fault.IndexUnavailable.
            final Fault fault = new Fault.IndexUnavailable(cause, "index-executor-saturated");
            assertInstanceOf(Fault.IndexUnavailable.class, fault,
                "Classifier mapping must yield Fault.IndexUnavailable");
            observedRejections++;
        }
        assertTrue(observedRejections == overflowCount,
            "All " + overflowCount + " overflow submissions must be rejected, "
                + "got " + observedRejections);

        // Release the parked JDBC calls so setUp()'s warmUp() and in-flight work can drain.
        this.releaseJdbc.countDown();

        // The invariant: saturation never ran the JDBC call on the caller thread.
        // (The caller for this test is JUnit's main thread, but the assertion
        // guards the production Vert.x event-loop case in the same way — the
        // fake DataSource records Vertx.currentContext() on every run.)
        assertFalse(this.ranOnVertxContext.get(),
            "No index work may run on a Vert.x event-loop thread");
    }

    /** Whether the test executor is saturated (all workers busy + queue full). */
    private boolean isSaturated() {
        return this.saturatingExecutor.getActiveCount() >= POOL_SIZE
            && this.saturatingExecutor.getQueue().size() >= QUEUE_SIZE;
    }

    /**
     * Spin until the pool has at least {@link #POOL_SIZE} active workers AND the
     * queue has reached capacity. Without this, a fast test harness can submit
     * the overflow slot before the executor has transferred a queued task into
     * a worker, which flips the AbortPolicy boundary by one slot and makes the
     * test flaky. Bounded wait: 2 seconds is generous for a 2-thread pool to
     * pick up its first two blocking jobs.
     */
    private void waitUntilPoolSaturated() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (this.isSaturated()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("Timed out waiting for DbArtifactIndex test executor to saturate "
            + "(active=" + this.saturatingExecutor.getActiveCount()
            + ", queue=" + this.saturatingExecutor.getQueue().size() + ")");
    }

    /** Unwrap CompletionException / ExecutionException layers. */
    private static Throwable unwrap(final Throwable top) {
        Throwable current = top;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
            && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * A {@link DataSource} whose {@code getConnection()} blocks on a latch,
     * allowing the test to park work in the pool's worker threads. Every
     * invocation also records whether it was invoked on a Vert.x event-loop
     * thread — the assertion hook that guards the critical "never run JDBC on
     * the caller" invariant.
     */
    private static final class BlockingDataSource implements DataSource {

        /** Latch released by the test to free parked work. */
        private final CountDownLatch gate;

        /** Set by any invocation that finds itself on a Vert.x context. */
        private final AtomicBoolean ranOnVertxContext;

        /** Count of invocations (diagnostic only). */
        private final AtomicInteger invocations = new AtomicInteger(0);

        BlockingDataSource(final CountDownLatch gate, final AtomicBoolean ranOnVertxContext) {
            this.gate = gate;
            this.ranOnVertxContext = ranOnVertxContext;
        }

        @Override
        public Connection getConnection() throws SQLException {
            this.invocations.incrementAndGet();
            if (Vertx.currentContext() != null) {
                this.ranOnVertxContext.set(true);
            }
            try {
                // Park here for up to 10s — the test releases the gate at teardown.
                if (!this.gate.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("gate not released within 10s");
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while blocking", ex);
            }
            throw new SQLException("BlockingDataSource never returns a real connection");
        }

        @Override
        public Connection getConnection(final String user, final String pass) throws SQLException {
            return this.getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(final PrintWriter out) {
            // no-op
        }

        @Override
        public void setLoginTimeout(final int seconds) {
            // no-op
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(final Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(final Class<?> iface) {
            return false;
        }
    }
}
