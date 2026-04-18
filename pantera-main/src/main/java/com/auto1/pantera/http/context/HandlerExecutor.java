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

import com.auto1.pantera.http.misc.ConfigDefaults;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared worker pool for Vert.x HTTP API handlers.
 *
 * <p>Every task submitted here carries the submitting thread's Log4j2
 * {@link org.apache.logging.log4j.ThreadContext} and the Elastic APM active
 * span through to the runner thread, via
 * {@link ContextualExecutor#contextualize(java.util.concurrent.Executor)}.
 *
 * <p><strong>Use as the executor argument to {@code CompletableFuture.*Async}
 * in every {@code api/v1/} handler</strong> — do <em>not</em> use
 * {@link java.util.concurrent.ForkJoinPool#commonPool()} (no context
 * propagation) — context propagation is handled by
 * {@link ContextualExecutor} at the pool boundary.
 *
 * <p>§4.4 of {@code docs/analysis/v2.2-target-architecture.md} makes the
 * <em>pool boundary</em> — not the per-call wrapper — responsible for
 * propagating ECS fields + APM trace context. Each handler migrated under
 * WI-post-03d submits its blocking callable via
 * {@link java.util.concurrent.CompletableFuture#supplyAsync(
 * java.util.function.Supplier, java.util.concurrent.Executor)} with
 * {@link #get()} as the executor; the wrapper carries {@code trace.id},
 * {@code user.name}, {@code client.ip} and the APM span onto the worker
 * thread automatically.
 *
 * <h2>Pool configuration</h2>
 * <ul>
 *   <li><b>Threads:</b> {@code max(4, availableProcessors())}. Tunable via
 *       system property {@code pantera.handler.executor.threads} /
 *       env var {@code PANTERA_HANDLER_EXECUTOR_THREADS}.</li>
 *   <li><b>Queue:</b> bounded {@link ArrayBlockingQueue} of size
 *       {@value #DEFAULT_QUEUE_SIZE} (env override
 *       {@code PANTERA_HANDLER_EXECUTOR_QUEUE}). Bounded so a misbehaving
 *       DB makes handler backpressure <em>visible</em> (503 / RejectedExecution)
 *       rather than swallowing requests into an unbounded queue with
 *       increasing latency.</li>
 *   <li><b>Rejection policy:</b>
 *       {@link ThreadPoolExecutor.AbortPolicy} — the caller sees a
 *       {@link java.util.concurrent.RejectedExecutionException} which
 *       {@code CompletableFuture.supplyAsync} wraps into a failed future,
 *       surfacing as HTTP 500 through the existing {@code .onFailure}
 *       paths. Callers that want graceful degradation can catch and map
 *       that to 503.</li>
 *   <li><b>Threads are daemon + named</b> {@code pantera-handler-N} so
 *       they do not block JVM shutdown and stand out in thread dumps.</li>
 * </ul>
 *
 * <h2>Singleton rationale</h2>
 * <p>A static holder (rather than DI) keeps the migration mechanical —
 * each handler call-site flips from
 * {@code ctx.vertx().executeBlocking(callable, false)} to
 * {@code CompletableFuture.supplyAsync(supplier, HandlerExecutor.get())}
 * without touching constructors or the {@code AsyncApiVerticle} wiring.
 * The pool is JVM-scoped; we have one process per node and one handler
 * chain per process, so a singleton is the right cardinality.
 *
 * @since 2.2.0
 */
public final class HandlerExecutor {

    /**
     * Minimum thread count regardless of CPU topology — small machines
     * still need enough workers to avoid head-of-line blocking on a
     * single blocking DB/auth call.
     */
    private static final int MIN_THREADS = 4;

    /**
     * Default queue size — env-overridable via
     * {@code PANTERA_HANDLER_EXECUTOR_QUEUE}. 1000 slots is large enough
     * to absorb typical UI bursts (dashboard refresh, paged user list)
     * while still signalling overload on a genuine stall.
     */
    private static final int DEFAULT_QUEUE_SIZE = 1000;

    /**
     * Keep-alive in seconds for idle core threads. Core threads time out
     * so the pool shrinks to 0 under zero load, avoiding needless
     * preallocation on a freshly booted node.
     */
    private static final long KEEP_ALIVE_SECONDS = 60L;

    /**
     * Configured thread count (cached at class init, read from
     * {@code PANTERA_HANDLER_EXECUTOR_THREADS} if present, otherwise
     * {@code max(MIN_THREADS, availableProcessors())}).
     */
    private static final int THREADS = ConfigDefaults.getInt(
        "PANTERA_HANDLER_EXECUTOR_THREADS",
        Math.max(MIN_THREADS, Runtime.getRuntime().availableProcessors())
    );

    /**
     * Configured queue size (cached at class init).
     */
    private static final int QUEUE_SIZE = ConfigDefaults.getInt(
        "PANTERA_HANDLER_EXECUTOR_QUEUE", DEFAULT_QUEUE_SIZE
    );

    /**
     * Underlying {@link ThreadPoolExecutor} — exposed as a package-private
     * field so {@link #queueSize()} / {@link #activeCount()} can read
     * diagnostic counters without casting the wrapped view.
     */
    private static final ThreadPoolExecutor BACKING;

    /**
     * Contextualised view of {@link #BACKING} — every task submitted
     * through {@link #get()} has its caller's ThreadContext + APM span
     * restored on the runner thread.
     *
     * <p>While WI-post-03a lands a richer {@code ContextualExecutorService}
     * that also wraps {@code submit}/{@code invokeAll}, this WI uses the
     * {@link java.util.concurrent.Executor}-level wrapper which is
     * sufficient for {@code CompletableFuture.*Async} — they all call
     * {@link java.util.concurrent.Executor#execute(Runnable)} underneath.
     * The coordinator will upgrade this call site to the full
     * {@code ExecutorService} wrapper once WI-post-03a ships.
     */
    private static final ExecutorService POOL;

    static {
        BACKING = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE),
            new NamedDaemonThreadFactory("pantera-handler"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        BACKING.allowCoreThreadTimeOut(true);
        POOL = new ContextualExecutorAdapter(BACKING);
    }

    private HandlerExecutor() {
        // utility class; not instantiable
    }

    /**
     * Return the shared handler executor.
     *
     * <p>Every Vert.x API handler submits its blocking callable via
     * {@code CompletableFuture.supplyAsync(supplier, HandlerExecutor.get())}
     * so the caller's ThreadContext and APM span propagate automatically.
     *
     * @return non-null executor service that wraps every submitted task
     *     with {@link ContextualExecutor#contextualize(
     *     java.util.concurrent.Executor)}
     */
    public static ExecutorService get() {
        return POOL;
    }

    /**
     * Current depth of the backing task queue.
     *
     * <p>Exported for Micrometer /
     * {@code pantera-main/src/main/java/com/auto1/pantera/metrics/} so
     * operators can chart handler backpressure; also handy for diagnostic
     * logs gated behind DEBUG.
     *
     * @return number of tasks waiting to run
     */
    public static int queueSize() {
        return BACKING.getQueue().size();
    }

    /**
     * Approximate number of worker threads currently executing a task.
     *
     * @return count of actively-running workers
     */
    public static int activeCount() {
        return BACKING.getActiveCount();
    }

    /**
     * Configured pool size — exposed for tests and diagnostics.
     *
     * @return the fixed thread count
     */
    public static int poolSize() {
        return THREADS;
    }

    /**
     * Configured queue capacity — exposed for the saturation test.
     *
     * @return max number of queued tasks
     */
    public static int queueCapacity() {
        return QUEUE_SIZE;
    }

    /**
     * {@link ThreadFactory} that produces daemon threads with a
     * descriptive name prefix.
     *
     * <p>Daemon so a stuck handler never holds up JVM shutdown; named so
     * thread dumps immediately reveal which worker pool is saturated.
     */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {

        /**
         * Monotonic counter for thread IDs, shared across the pool's
         * lifetime — matches the convention of
         * {@link Executors#defaultThreadFactory()} but with a
         * human-readable prefix.
         */
        private final AtomicInteger counter = new AtomicInteger(1);

        /**
         * Name prefix; final full name is {@code prefix-N}.
         */
        private final String prefix;

        /**
         * Ctor.
         *
         * @param prefix descriptive prefix, e.g. {@code pantera-handler}
         */
        NamedDaemonThreadFactory(final String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public Thread newThread(final Runnable run) {
            final Thread thread = new Thread(
                run, this.prefix + "-" + this.counter.getAndIncrement()
            );
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Minimal {@link ExecutorService} facade that routes every submission
     * through {@link ContextualExecutor#contextualize(
     * java.util.concurrent.Executor)}.
     *
     * <p><strong>Scope:</strong> {@code CompletableFuture.*Async} and our
     * handler call-sites only use {@link #execute(Runnable)}; the
     * remaining {@link ExecutorService} methods delegate to the raw
     * backing pool (no ThreadContext / APM propagation) and are retained
     * only so this class honours the interface contract.
     *
     * <p>When WI-post-03a lands {@code ContextualExecutorService} in
     * {@code pantera-core/http/context/}, swap this adapter for a direct
     * {@code ContextualExecutorService.wrap(backing)} call and delete
     * this inner class. That fills in the {@code submit}/{@code invokeAll}
     * propagation holes for free.
     */
    private static final class ContextualExecutorAdapter
        extends java.util.concurrent.AbstractExecutorService {

        /**
         * Contextualising {@link java.util.concurrent.Executor} — the
         * {@code execute(Runnable)} path used by every
         * {@code CompletableFuture.*Async} call.
         */
        private final java.util.concurrent.Executor contextual;

        /**
         * Raw pool — owns lifecycle (shutdown / awaitTermination) and
         * serves the {@code submit}/{@code invokeAll} fallbacks.
         */
        private final ExecutorService backing;

        /**
         * Ctor.
         *
         * @param pool raw backing thread pool; its lifecycle is owned
         *     by this adapter (shutdown / awaitTermination delegate)
         */
        ContextualExecutorAdapter(final ExecutorService pool) {
            this.backing = Objects.requireNonNull(pool, "pool");
            this.contextual = ContextualExecutor.contextualize(pool);
        }

        @Override
        public void execute(final Runnable task) {
            this.contextual.execute(task);
        }

        @Override
        public void shutdown() {
            this.backing.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return this.backing.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return this.backing.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return this.backing.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout,
            final TimeUnit unit) throws InterruptedException {
            return this.backing.awaitTermination(timeout, unit);
        }
    }
}
