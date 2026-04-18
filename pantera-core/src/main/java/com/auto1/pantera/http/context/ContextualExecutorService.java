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

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Scope;
import co.elastic.apm.api.Span;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.ThreadContext;

/**
 * {@link ExecutorService} wrapper that contextualises <em>every</em> task-submission
 * method — not just {@link #execute(Runnable)}.
 *
 * <p>Background: {@link ContextualExecutor#contextualize(java.util.concurrent.Executor)}
 * propagates the caller's Log4j2 {@link ThreadContext} (ECS field carrier) and the
 * Elastic APM active {@link Span} across thread hops, but it only targets the bare
 * {@link java.util.concurrent.Executor} interface — i.e. {@code execute(Runnable)}.
 *
 * <p>When downstream code expects an {@link ExecutorService} and routes tasks through
 * {@link #submit(Callable)}, {@link #submit(Runnable, Object)}, {@link #submit(Runnable)},
 * {@link #invokeAll(Collection)} or {@link #invokeAny(Collection)}, those calls bypass
 * the contextualising wrapper and run on the runner thread with an empty ThreadContext
 * and no APM context — silently dropping ECS fields from every log line emitted by the
 * task and breaking distributed tracing.
 *
 * <p>This class closes that gap. It wraps an arbitrary delegate {@link ExecutorService}
 * so that:
 * <ul>
 *   <li>{@code execute(Runnable)} is routed through {@link ContextualExecutor}
 *       (same behaviour as the bare-Executor wrapper);</li>
 *   <li>every {@code submit(...)}, {@code invokeAll(...)} and {@code invokeAny(...)}
 *       overload snapshots {@link ThreadContext} and the active APM {@link Span} on
 *       the submitting thread at call time, then decorates the task(s) so that the
 *       snapshot is installed on the runner thread for the task's duration and
 *       restored in {@code finally} (even on exception);</li>
 *   <li>lifecycle methods ({@code shutdown}, {@code shutdownNow},
 *       {@code awaitTermination}, {@code isShutdown}, {@code isTerminated}) delegate
 *       directly to the underlying pool.</li>
 * </ul>
 *
 * <p>This wrapper is the idiomatic boundary for thread pools in pantera from
 * v2.2.0 onward. Use it at every {@link ExecutorService} construction site where
 * any code path — {@code CompletableFuture.supplyAsync}, {@code executor.submit},
 * {@code invokeAll}, etc. — needs ECS/APM context propagation.
 *
 * @since 2.2.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidCatchingGenericException"})
public final class ContextualExecutorService implements ExecutorService {

    /**
     * Underlying pool — target of lifecycle calls and of the raw
     * {@code submit/invokeAll/invokeAny} collection dispatches.
     */
    private final ExecutorService delegate;

    /**
     * {@link java.util.concurrent.Executor} view of {@link #delegate} produced by
     * {@link ContextualExecutor#contextualize(java.util.concurrent.Executor)}. Used
     * to route {@link #execute(Runnable)} calls through the same
     * snapshot-and-restore machinery used by {@link ContextualExecutor}.
     */
    private final java.util.concurrent.Executor contextualExec;

    /**
     * Build a wrapper around {@code delegate}.
     *
     * @param delegate the backing executor service; must be non-null
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public ContextualExecutorService(final ExecutorService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.contextualExec = ContextualExecutor.contextualize(delegate);
    }

    /**
     * Static factory — equivalent to {@code new ContextualExecutorService(delegate)}.
     *
     * @param delegate the backing executor service; must be non-null
     * @return a fresh contextualising wrapper
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public static ContextualExecutorService wrap(final ExecutorService delegate) {
        return new ContextualExecutorService(delegate);
    }

    // --- task submission ------------------------------------------------

    @Override
    public void execute(final Runnable command) {
        this.contextualExec.execute(command);
    }

    @Override
    public <T> Future<T> submit(final Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return this.delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(final Runnable task, final T result) {
        Objects.requireNonNull(task, "task");
        return this.delegate.submit(wrap(task), result);
    }

    @Override
    public Future<?> submit(final Runnable task) {
        Objects.requireNonNull(task, "task");
        return this.delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        final Collection<? extends Callable<T>> tasks
    ) throws InterruptedException {
        return this.delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        final Collection<? extends Callable<T>> tasks,
        final long timeout, final TimeUnit unit
    ) throws InterruptedException {
        return this.delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(
        final Collection<? extends Callable<T>> tasks
    ) throws InterruptedException, ExecutionException {
        return this.delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(
        final Collection<? extends Callable<T>> tasks,
        final long timeout, final TimeUnit unit
    ) throws InterruptedException, ExecutionException, TimeoutException {
        return this.delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    // --- lifecycle ------------------------------------------------------

    @Override
    public void shutdown() {
        this.delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return this.delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return this.delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return this.delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit)
        throws InterruptedException {
        return this.delegate.awaitTermination(timeout, unit);
    }

    // --- internals ------------------------------------------------------

    /**
     * Wrap a {@link Callable} so the submitting thread's ThreadContext + APM span
     * is snapshot at call time and restored on the runner thread for the task's
     * duration (including on exception).
     *
     * @param task original callable; must be non-null
     * @param <T> task result type
     * @return decorated callable that propagates ECS + APM context
     */
    private static <T> Callable<T> wrap(final Callable<T> task) {
        Objects.requireNonNull(task, "task");
        final Map<String, String> ctx = ThreadContext.getImmutableContext();
        final Span span = ElasticApm.currentSpan();
        return () -> callWithContext(task, ctx, span);
    }

    /**
     * Wrap a {@link Runnable} so the submitting thread's ThreadContext + APM span
     * is snapshot at call time and restored on the runner thread for the task's
     * duration (including on exception).
     *
     * @param task original runnable; must be non-null
     * @return decorated runnable that propagates ECS + APM context
     */
    private static Runnable wrap(final Runnable task) {
        Objects.requireNonNull(task, "task");
        final Map<String, String> ctx = ThreadContext.getImmutableContext();
        final Span span = ElasticApm.currentSpan();
        return () -> runWithContext(task, ctx, span);
    }

    /**
     * Wrap every {@link Callable} in a collection via {@link #wrap(Callable)}.
     *
     * @param tasks source collection (snapshotted at call time)
     * @param <T> common task result type
     * @return list of decorated callables, preserving order
     */
    private static <T> List<Callable<T>> wrapAll(
        final Collection<? extends Callable<T>> tasks
    ) {
        Objects.requireNonNull(tasks, "tasks");
        final List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (final Callable<T> task : tasks) {
            wrapped.add(wrap(task));
        }
        return wrapped;
    }

    /**
     * Install the captured ThreadContext + APM span on the current thread, run the
     * {@link Runnable}, and restore the runner's prior ThreadContext unconditionally.
     *
     * @param task runnable to execute
     * @param ctx ThreadContext snapshot captured at submit time
     * @param span APM span captured at submit time
     */
    private static void runWithContext(
        final Runnable task,
        final Map<String, String> ctx,
        final Span span
    ) {
        final Map<String, String> prior = ThreadContext.getImmutableContext();
        ThreadContext.clearMap();
        if (!ctx.isEmpty()) {
            ThreadContext.putAll(ctx);
        }
        try (Scope ignored = span.activate()) {
            task.run();
        } finally {
            ThreadContext.clearMap();
            if (!prior.isEmpty()) {
                ThreadContext.putAll(prior);
            }
        }
    }

    /**
     * Install the captured ThreadContext + APM span on the current thread, call the
     * {@link Callable}, and restore the runner's prior ThreadContext unconditionally.
     *
     * @param task callable to execute
     * @param ctx ThreadContext snapshot captured at submit time
     * @param span APM span captured at submit time
     * @param <T> task result type
     * @return the callable's result
     * @throws Exception whatever the callable throws; propagated after restore
     */
    private static <T> T callWithContext(
        final Callable<T> task,
        final Map<String, String> ctx,
        final Span span
    ) throws Exception {
        final Map<String, String> prior = ThreadContext.getImmutableContext();
        ThreadContext.clearMap();
        if (!ctx.isEmpty()) {
            ThreadContext.putAll(ctx);
        }
        try (Scope ignored = span.activate()) {
            return task.call();
        } finally {
            ThreadContext.clearMap();
            if (!prior.isEmpty()) {
                ThreadContext.putAll(prior);
            }
        }
    }
}
