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
package com.auto1.pantera.http.log;

import org.apache.logging.log4j.ThreadContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Wraps a {@link Callable} and propagates the Log4j2 MDC context from the calling thread
 * into the worker thread that executes the callable.
 *
 * <p>Vert.x {@code executeBlocking} dispatches work to a worker-thread pool. Those threads
 * are reused across requests and never enter the request-handling pipeline, so they have an
 * empty {@link ThreadContext}. Without propagation, any log call inside the blocked lambda
 * is missing {@code trace.id}, {@code client.ip}, and {@code user.name} — making ECS
 * correlation impossible.
 *
 * <p>Usage:
 * <pre>{@code
 * ctx.vertx().<JsonObject>executeBlocking(
 *     MdcPropagatingCallable.wrap(() -> {
 *         // trace.id / client.ip / user.name are available here
 *         return buildExpensiveResult();
 *     })
 * );
 * }</pre>
 *
 * <p>Thread safety: each instance is single-use. The MDC snapshot is captured once at
 * construction time (on the caller thread) and restored/cleaned up during {@link #call()}.
 *
 * @param <T> Return type of the wrapped callable
 * @since 1.21.0
 */
public final class MdcPropagatingCallable<T> implements Callable<T> {

    /**
     * MDC snapshot captured on the caller (event-loop) thread.
     */
    private final Map<String, String> snapshot;

    /**
     * The actual work to execute on the worker thread.
     */
    private final Callable<T> delegate;

    /**
     * Ctor — captures the current MDC state immediately.
     *
     * @param delegate Work to run on the worker thread
     */
    public MdcPropagatingCallable(final Callable<T> delegate) {
        this.delegate = delegate;
        final Map<String, String> ctx = ThreadContext.getImmutableContext();
        this.snapshot = ctx.isEmpty() ? Collections.emptyMap() : new HashMap<>(ctx);
    }

    /**
     * Factory convenience method.
     *
     * @param delegate Work to run on the worker thread
     * @param <T> Return type
     * @return Wrapped callable
     */
    public static <T> MdcPropagatingCallable<T> wrap(final Callable<T> delegate) {
        return new MdcPropagatingCallable<>(delegate);
    }

    @Override
    public T call() throws Exception {
        if (!this.snapshot.isEmpty()) {
            ThreadContext.putAll(this.snapshot);
        }
        try {
            return this.delegate.call();
        } finally {
            if (!this.snapshot.isEmpty()) {
                for (final String key : this.snapshot.keySet()) {
                    ThreadContext.remove(key);
                }
            }
        }
    }
}
