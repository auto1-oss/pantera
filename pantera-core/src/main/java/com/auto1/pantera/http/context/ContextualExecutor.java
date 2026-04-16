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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.ThreadContext;

/**
 * Wraps any {@link Executor} so that tasks dispatched through it inherit the
 * caller's Log4j2 {@link ThreadContext} (the ECS field carrier used by
 * {@link RequestContext}) and the caller's Elastic APM active {@link Span}.
 *
 * <p>Implements §4.4 of {@code docs/analysis/v2.2-target-architecture.md}: the
 * single replacement for the old per-call {@code MdcPropagation.withMdc*}
 * helpers. Wire this once at each thread-pool boundary (Vert.x worker pool,
 * the drain executor, the DB index executor, the Quartz worker thread factory,
 * any {@code ForkJoinPool} on the hot path) — from then on, every
 * {@code CompletableFuture.supplyAsync(..., ctxExecutor)} or
 * {@code executor.submit} propagates ECS fields and the APM trace context
 * transparently.
 *
 * <p>Capture semantics:
 * <ol>
 *   <li>{@link ThreadContext#getImmutableContext()} snapshot is taken on the
 *       <em>calling</em> thread at the moment {@link Executor#execute} is
 *       invoked. The snapshot is a defensive copy — mutating the caller's
 *       ThreadContext after dispatch does not affect the task.
 *   <li>{@link ElasticApm#currentSpan()} is captured at the same moment. When
 *       the APM agent is not attached this returns a no-op span, making this
 *       safe for tests and for deployments without the agent.
 *   <li>On the runner thread, the snapshot is installed <em>after</em> saving
 *       the runner's prior ThreadContext. The span is activated in a
 *       try-with-resources so the APM scope is always released. The prior
 *       ThreadContext is restored in {@code finally}, even if the task throws.
 * </ol>
 *
 * <p>The wrapper itself is stateless; the snapshot lives only in the closure
 * created per {@link Executor#execute} call.
 *
 * @since 2.2.0
 */
public final class ContextualExecutor {

    private ContextualExecutor() {
        // utility class; not instantiable
    }

    /**
     * Produce an {@link Executor} that, for every task it accepts, snapshots
     * the caller's {@link ThreadContext} and current APM {@link Span} and
     * restores them on the runner thread for the duration of the task.
     *
     * @param delegate the backing executor; must be non-null. Its threading
     *                 and rejection behaviour is unchanged — this wrapper
     *                 only decorates the {@link Runnable} passed through.
     * @return a non-null executor that propagates ECS + APM context
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public static Executor contextualize(final Executor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return task -> {
            Objects.requireNonNull(task, "task");
            final Map<String, String> ctx = ThreadContext.getImmutableContext();
            final Span span = ElasticApm.currentSpan();
            delegate.execute(() -> runWithContext(task, ctx, span));
        };
    }

    /**
     * Run {@code task} on the current thread with the captured ThreadContext
     * and APM span installed; restore the prior ThreadContext unconditionally
     * when the task returns or throws.
     *
     * <p>Extracted so the happy-path lambda in {@link #contextualize(Executor)}
     * is a single-line dispatch, keeping PMD / Checkstyle metrics low.
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
}
