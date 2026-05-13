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

import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Result;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>When the semaphore is full, {@link #run(Supplier)} returns
 * {@link Result#err(Fault)} with a {@link Fault.Overload} carrying the
 * repo name and suggested retry-after duration.
 *
 * @since 2.2.0
 */
public final class RepoBulkhead {

    private final String repo;
    private final Semaphore inFlight;
    private final BulkheadLimits limits;
    private final Executor drainExecutor;
    private final AtomicLong drainDropCount;

    /**
     * Construct a per-repo bulkhead.
     *
     * @param repo           Repository name (used in {@link Fault.Overload} and metrics).
     * @param limits         Concurrency limits for this repository.
     * @param ctxWorkerPool  A {@link com.auto1.pantera.http.context.ContextualExecutor}-wrapped
     *                       executor used as the base for the per-repo drain pool. Currently
     *                       unused directly; the drain pool is constructed internally with its
     *                       own bounded queue. Retained for future per-repo worker pool support.
     */
    public RepoBulkhead(
        final String repo,
        final BulkheadLimits limits,
        final Executor ctxWorkerPool
    ) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ctxWorkerPool, "ctxWorkerPool");
        this.inFlight = new Semaphore(limits.maxConcurrent());
        this.drainDropCount = new AtomicLong();
        final int drainThreads = Math.max(2, limits.maxConcurrent() / 50);
        this.drainExecutor = buildDrainExecutor(repo, limits.maxQueueDepth(), drainThreads);
    }

    /**
     * Execute an operation within this bulkhead's concurrency limit.
     *
     * <p>If the semaphore cannot be acquired immediately, returns a
     * completed future with {@link Result#err(Fault)} containing
     * {@link Fault.Overload}. Otherwise, the operation is invoked and
     * the semaphore is released when the returned stage completes
     * (whether normally or exceptionally).
     *
     * @param op  Supplier producing the async operation to protect.
     * @param <T> Result value type.
     * @return A completion stage with the operation's result or an overload fault.
     */
    public <T> CompletionStage<Result<T>> run(final Supplier<CompletionStage<Result<T>>> op) {
        if (!this.inFlight.tryAcquire()) {
            return CompletableFuture.completedFuture(
                Result.err(new Fault.Overload(this.repo, this.limits.retryAfter()))
            );
        }
        try {
            return op.get().whenComplete((r, e) -> this.inFlight.release());
        } catch (final RuntimeException ex) {
            this.inFlight.release();
            return CompletableFuture.failedFuture(ex);
        }
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
     * @return Active request count, between 0 and {@link BulkheadLimits#maxConcurrent()}.
     */
    public int activeCount() {
        return this.limits.maxConcurrent() - this.inFlight.availablePermits();
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
    public BulkheadLimits limits() {
        return this.limits;
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
