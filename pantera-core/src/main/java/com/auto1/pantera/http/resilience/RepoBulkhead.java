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
package com.auto1.pantera.http.resilience;

import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.log.EcsLogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Per-repository bulkhead that bounds the number of concurrent in-flight
 * requests and provides a dedicated drain executor for background body
 * draining.
 *
 * <p>Implements design principle 7 ("per-repo blast radius") from
 * {@code docs/analysis/v2.2-target-architecture.md} section 7. Each
 * repository gets exactly one {@code RepoBulkhead} at start-up; saturation
 * in one repository does not starve another.
 *
 * <p>The permit ceiling is held by an {@link AdaptivePermitController}.
 * When {@link AdaptiveBulkheadLimits#adaptive()} is {@code true} it grows
 * from the initial permits to the maximum and never falls; when
 * {@code false} it is fixed.
 *
 * <p>When every permit is in use, {@link #run(Supplier)} does not refuse:
 * the operation waits for a permit, oldest first. A burst of requests is
 * spread over the permits instead of being failed. Only when no permit
 * comes within the wait bound, or the queue is full, does it return
 * {@link Result#err(Fault)} with a {@link Fault.Overload} carrying the
 * repo name and the suggested retry-after duration.
 *
 * @since 2.2.0
 */
public final class RepoBulkhead {

    /**
     * Longest time an operation waits for a permit. A client must still get
     * its answer before it gives up on its own: Gradle waits 30 s for a
     * response by default ({@code org.gradle.internal.http.socketTimeout}),
     * and a proxy request as a whole is bounded by the proxy timeout, 60 s
     * by default. A request that waited the full bound has 10 s left for its
     * fetch under the first and 40 s under the second.
     */
    private static final Duration MAX_WAIT = Duration.ofSeconds(20);

    /**
     * Shortest time between two overflow records of one repository.
     */
    private static final long OVERFLOW_LOG_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final String repo;
    private final AdaptiveBulkheadLimits limits;
    private final AdaptivePermitController controller;
    private final Executor drainExecutor;
    private final AtomicLong drainDropCount;

    /**
     * Runs operations that had to wait for a permit.
     */
    private final Executor resumeExecutor;

    /**
     * Longest time an operation waits for a permit.
     */
    private final Duration maxWait;

    /**
     * Operations refused since the last overflow record.
     */
    private final AtomicLong refused;

    /**
     * Time of the last overflow record, {@link System#nanoTime()}.
     */
    private final AtomicLong overflowLogged;

    /**
     * Construct a per-repo bulkhead with adaptive limits.
     *
     * @param repo           Repository name (used in {@link Fault.Overload} and metrics).
     * @param limits         Adaptive concurrency limits.
     * @param ctxWorkerPool  Executor that runs the operations which had to wait for a permit.
     *                       The drain pool is separate and constructed internally with its
     *                       own bounded queue.
     */
    public RepoBulkhead(
        final String repo,
        final AdaptiveBulkheadLimits limits,
        final Executor ctxWorkerPool
    ) {
        this(repo, limits, ctxWorkerPool, RepoBulkhead.MAX_WAIT);
    }

    /**
     * Construct a per-repo bulkhead with adaptive limits and its own wait
     * bound.
     *
     * @param repo           Repository name (used in {@link Fault.Overload} and metrics).
     * @param limits         Adaptive concurrency limits.
     * @param ctxWorkerPool  Executor that runs the operations which had to wait for a permit.
     * @param maxWait        Longest time an operation waits for a permit before it is refused.
     */
    public RepoBulkhead(
        final String repo,
        final AdaptiveBulkheadLimits limits,
        final Executor ctxWorkerPool,
        final Duration maxWait
    ) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.resumeExecutor = Objects.requireNonNull(ctxWorkerPool, "ctxWorkerPool");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        this.controller = new AdaptivePermitController(repo, limits);
        this.drainDropCount = new AtomicLong();
        this.refused = new AtomicLong();
        this.overflowLogged = new AtomicLong(System.nanoTime() - RepoBulkhead.OVERFLOW_LOG_NANOS);
        final int drainThreads = Math.max(2, limits.maxPermits() / 50);
        this.drainExecutor = buildDrainExecutor(repo, limits.maxQueueDepth(), drainThreads);
    }

    /**
     * Construct a per-repo bulkhead from legacy fixed {@link BulkheadLimits}.
     * Retained for source compatibility with call sites that have not yet
     * migrated to the adaptive shape.
     *
     * @param repo           Repository name.
     * @param legacy         Legacy fixed limits.
     * @param ctxWorkerPool  See adaptive ctor.
     */
    public RepoBulkhead(
        final String repo,
        final BulkheadLimits legacy,
        final Executor ctxWorkerPool
    ) {
        this(repo, AdaptiveBulkheadLimits.fromLegacy(legacy), ctxWorkerPool);
    }

    /**
     * Execute an operation within this bulkhead's concurrency limit.
     *
     * <p>With a permit free the operation is invoked at once, on the calling
     * thread. Otherwise it waits for one and is then invoked on the resume
     * executor, under the logging context its caller had. A queue of
     * operations that complete at once would otherwise nest one frame deeper
     * with each of them, on the thread of the request that released the
     * permit. If no permit comes within the wait bound, or the queue of
     * waiting operations is full, returns {@link Result#err(Fault)}
     * containing {@link Fault.Overload}.
     * The gate is released when the returned stage completes (normally or
     * exceptionally).
     *
     * @param op  Supplier producing the async operation to protect.
     * @param <T> Result value type.
     * @return A completion stage with the operation's result or an overload fault.
     */
    public <T> CompletionStage<Result<T>> run(final Supplier<CompletionStage<Result<T>>> op) {
        final CompletableFuture<Boolean> permit = this.controller.acquire(this.maxWait);
        final CompletionStage<Result<T>> stage;
        if (!permit.isDone()) {
            stage = this.queued(op, permit);
        } else if (permit.getNow(Boolean.FALSE)) {
            stage = this.admitted(op);
        } else {
            stage = CompletableFuture.completedFuture(this.overloaded());
        }
        return stage;
    }

    /**
     * Park an operation until its permit is decided. Both outcomes continue
     * on the resume executor: the permit is granted on the thread of the
     * request that released it, the wait bound expires on the timer thread
     * of the JDK, and neither may run this request's work.
     *
     * @param op     Supplier producing the async operation.
     * @param permit Completes with whether a permit was reserved.
     * @param <T> Result value type.
     * @return Stage with the operation's result or an overload fault.
     */
    private <T> CompletionStage<Result<T>> queued(
        final Supplier<CompletionStage<Result<T>>> op,
        final CompletableFuture<Boolean> permit
    ) {
        final CompletableFuture<Result<T>> queued = new CompletableFuture<>();
        final Runnable resume = RepoBulkhead.underCallerContext(
            () -> this.admitted(op).whenComplete(
                (result, error) -> {
                    if (error == null) {
                        queued.complete(result);
                    } else {
                        queued.completeExceptionally(error);
                    }
                }
            )
        );
        final Runnable refuse = RepoBulkhead.underCallerContext(
            () -> queued.complete(this.overloaded())
        );
        permit.whenComplete(
            (granted, error) -> {
                final boolean reserved = Boolean.TRUE.equals(granted);
                try {
                    this.resumeExecutor.execute(reserved ? resume : refuse);
                } catch (final RejectedExecutionException ex) {
                    if (reserved) {
                        this.controller.release();
                    }
                    queued.complete(this.overloaded());
                }
            }
        );
        return queued;
    }

    /**
     * Number of operations waiting for a permit.
     *
     * @return Waiting operation count.
     */
    public int waitingCount() {
        return this.controller.waitingCount();
    }

    /**
     * Invoke an operation that holds a permit; release the permit and report
     * the outcome when its stage completes.
     *
     * @param op  Supplier producing the async operation.
     * @param <T> Result value type.
     * @return The operation's stage.
     */
    private <T> CompletionStage<Result<T>> admitted(
        final Supplier<CompletionStage<Result<T>>> op
    ) {
        final long startNanos = System.nanoTime();
        try {
            return op.get().whenComplete((result, error) -> {
                this.controller.release();
                final long latencyMs = elapsedMillis(startNanos);
                final boolean ok = error == null && result instanceof Result.Ok<?>;
                this.controller.observe(latencyMs, ok);
            });
        } catch (final RuntimeException ex) {
            this.controller.release();
            this.controller.observe(elapsedMillis(startNanos), false);
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * The refusal of an operation that got no permit. Counted, and logged at
     * most once in {@link #OVERFLOW_LOG_NANOS}: under overload a record per
     * refused request would bury everything else.
     *
     * @param <T> Result value type.
     * @return Overload fault.
     */
    private <T> Result<T> overloaded() {
        final long pending = this.refused.incrementAndGet();
        final long now = System.nanoTime();
        final long last = this.overflowLogged.get();
        if (now - last >= RepoBulkhead.OVERFLOW_LOG_NANOS
            && this.overflowLogged.compareAndSet(last, now)) {
            this.refused.addAndGet(-pending);
            EcsLogger.warn("com.auto1.pantera.http.resilience")
                .message(
                    String.format(
                        "Per-repo bulkhead of %s refused %d request(s) with 503: all %d permits in use and %d request(s) waiting for one (a request waits at most %d s, at most %d wait)",
                        this.repo, pending, this.controller.currentPermits(),
                        this.controller.waitingCount(), this.maxWait.toSeconds(),
                        this.limits.maxQueueDepth()
                    )
                )
                .eventCategory("web")
                .eventAction("bulkhead_overflow")
                .eventOutcome("failure")
                .field("event.reason", "bulkhead_full")
                .field("repository.name", this.repo)
                .field("log.source", "application")
                .log();
        }
        return Result.err(new Fault.Overload(this.repo, this.limits.retryAfter()));
    }

    /**
     * Bind a task to the logging context and tracing span of the calling
     * thread, without running it.
     *
     * @param task Task.
     * @return Task that installs the caller's context around its run.
     */
    private static Runnable underCallerContext(final Runnable task) {
        final AtomicReference<Runnable> bound = new AtomicReference<>();
        ContextualExecutor.contextualize(bound::set).execute(task);
        return bound.get();
    }

    /**
     * Repository name this bulkhead protects.
     *
     * @return Non-null repository name.
     */
    public String repo() {
        return this.repo;
    }

    /**
     * Number of permits currently held (in-flight requests).
     *
     * @return Active request count, between 0 and {@link #currentPermits()}.
     */
    public int activeCount() {
        return this.controller.inFlightCount();
    }

    /**
     * Current permit ceiling — the maximum number of in-flight requests
     * the gate will admit right now. Constant under fixed mode; AIMD-tuned
     * between {@link AdaptiveBulkheadLimits#minPermits()} and
     * {@link AdaptiveBulkheadLimits#maxPermits()} under adaptive mode.
     *
     * @return Current permit cap.
     */
    public int currentPermits() {
        return this.controller.currentPermits();
    }

    /**
     * The per-repo drain executor for background body draining.
     *
     * <p>Replaces the former process-wide static {@code DRAIN_EXECUTOR} that
     * lived in {@code GroupResolver}. Each repository's
     * drain pool is bounded independently so a slow-draining repo cannot
     * exhaust the drain capacity of other repos.
     *
     * @return Non-null executor for drain tasks.
     */
    public Executor drainExecutor() {
        return this.drainExecutor;
    }

    /**
     * Total count of drain tasks dropped because this repo's drain queue was full.
     *
     * @return Monotonic total of rejected drain tasks since this bulkhead was created.
     */
    public long drainDropCount() {
        return this.drainDropCount.get();
    }

    /**
     * The limits this bulkhead was configured with.
     *
     * @return Non-null limits record.
     */
    public AdaptiveBulkheadLimits limits() {
        return this.limits;
    }

    /**
     * The AIMD controller. Exposed for metrics + tests; production callers
     * should prefer the bulkhead's own delegating methods.
     *
     * @return Non-null adaptive controller.
     */
    public AdaptivePermitController controller() {
        return this.controller;
    }

    /**
     * Shut down the AIMD scheduler tick. Safe to call multiple times.
     * In-flight requests are unaffected and will release normally.
     */
    public void close() {
        this.controller.close();
    }

    private static long elapsedMillis(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private Executor buildDrainExecutor(
        final String repoName,
        final int queueDepth,
        final int threads
    ) {
        final AtomicLong dropCounter = this.drainDropCount;
        return new ThreadPoolExecutor(
            threads, threads,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueDepth),
            r -> {
                final Thread t = new Thread(
                    r, "drain-" + repoName + "-" + System.identityHashCode(r)
                );
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> dropCounter.incrementAndGet()
        );
    }
}
