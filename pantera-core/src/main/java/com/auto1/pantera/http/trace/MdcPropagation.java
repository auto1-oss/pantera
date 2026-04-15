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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Captures the current SLF4J MDC (trace.id, span.id, client.ip, etc.)
 * and restores it inside a callback running on a different thread.
 *
 * <p>MDC is backed by {@code ThreadLocal}, so state set on the Vert.x
 * event loop thread is NOT visible on worker threads used by
 * {@code executeBlocking}. Without this utility, logs emitted from
 * inside a blocking auth call would be missing all request-scoped
 * fields.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * ctx.vertx().executeBlocking(
 *     MdcPropagation.withMdc(() -> auth.user(name, pass)),
 *     false
 * );
 * }</pre>
 * The captured MDC is the one present at the call site (event loop
 * thread). On the worker thread the captured map is installed before
 * the callable runs and fully cleared after.</p>
 *
 * @since 2.1.0
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * Wrap a {@link Callable} so it restores the caller's MDC context
     * on whichever thread it ends up running.
     *
     * @param callable The original callable
     * @param <T> Return type
     * @return A callable that installs + clears MDC around the original
     */
    public static <T> Callable<T> withMdc(final Callable<T> callable) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return callable.call();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link Function} for use in {@code CompletableFuture.thenCompose()} so
     * it restores the caller's MDC context on whichever thread the callback runs.
     *
     * <p>Usage:
     * <pre>{@code
     * future.thenCompose(MdcPropagation.withMdc(value -> {
     *     // MDC is restored here regardless of which thread executes this
     *     return anotherFuture(value);
     * }))
     * }</pre>
     *
     * <p>The prior MDC state of the executing thread is saved and restored after
     * the function completes, so pool threads are not polluted with request context.
     *
     * @param fn The original function
     * @param <T> Input type
     * @param <U> Output future type
     * @return A function that installs + restores MDC around the original
     */
    public static <T, U> Function<T, CompletableFuture<U>> withMdc(
        final Function<T, CompletableFuture<U>> fn
    ) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return value -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return fn.apply(value);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a plain {@link Function} (for use in {@code CompletableFuture.thenApply()}) so
     * it restores the caller's MDC context on whichever thread the callback runs.
     *
     * <p>Usage:
     * <pre>{@code
     * future.thenApply(MdcPropagation.withMdcFunction(value -> {
     *     // MDC is restored here
     *     return transform(value);
     * }))
     * }</pre>
     *
     * @param fn The original function
     * @param <T> Input type
     * @param <R> Return type
     * @return A function that installs + restores MDC around the original
     */
    public static <T, R> Function<T, R> withMdcFunction(final Function<T, R> fn) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return value -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return fn.apply(value);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link BiConsumer} for use in {@code CompletableFuture.whenComplete()} so
     * it restores the caller's MDC context on whichever thread the callback runs.
     *
     * <p>Usage:
     * <pre>{@code
     * future.whenComplete(MdcPropagation.withMdc((result, err) -> {
     *     // MDC is restored here regardless of which thread executes this
     *     recordMetrics(result, err);
     * }))
     * }</pre>
     *
     * <p>The prior MDC state of the executing thread is saved and restored after
     * the consumer completes, so pool threads are not polluted with request context.
     *
     * @param consumer The original bi-consumer
     * @param <T> Result type
     * @param <U> Throwable type
     * @return A bi-consumer that installs + restores MDC around the original
     */
    public static <T, U extends Throwable> BiConsumer<T, U> withMdcBiConsumer(
        final BiConsumer<T, U> consumer
    ) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return (result, err) -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                consumer.accept(result, err);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link BiFunction} for use in {@code CompletableFuture.handle()} so
     * it restores the caller's MDC context on whichever thread the callback runs.
     *
     * <p>Usage:
     * <pre>{@code
     * future.handle(MdcPropagation.withMdcBiFunction((result, err) -> {
     *     // MDC is restored here regardless of which thread executes this
     *     return transform(result, err);
     * }))
     * }</pre>
     *
     * <p>The prior MDC state of the executing thread is saved and restored after
     * the function completes, so pool threads are not polluted with request context.
     *
     * @param fn The original bi-function
     * @param <T> Result type
     * @param <U> Throwable type
     * @param <R> Return type
     * @return A bi-function that installs + restores MDC around the original
     */
    public static <T, U extends Throwable, R> BiFunction<T, U, R> withMdcBiFunction(
        final BiFunction<T, U, R> fn
    ) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return (result, err) -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return fn.apply(result, err);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link Runnable} so it restores the caller's MDC context
     * on whichever thread it ends up running.
     *
     * @param runnable The original runnable
     * @return A runnable that installs + clears MDC around the original
     */
    public static Runnable withMdc(final Runnable runnable) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap an RxJava {@link io.reactivex.functions.Function} so it restores
     * the caller's MDC context on whichever thread the operator executes.
     *
     * <p>Use for RxJava {@code Maybe.map}, {@code Single.map},
     * {@code Flowable.map} and similar — whose continuations run on the
     * thread that completed the upstream signal (often a worker pool with
     * empty MDC).</p>
     *
     * @param fn The original RxJava function
     * @param <T> Input type
     * @param <R> Return type
     * @return A function that installs + restores MDC around the original
     */
    public static <T, R> io.reactivex.functions.Function<T, R> withMdcRxFunction(
        final io.reactivex.functions.Function<T, R> fn
    ) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return value -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return fn.apply(value);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link Supplier} so it restores the caller's MDC context on
     * whichever thread executes it. Primarily for use with
     * {@link CompletableFuture#supplyAsync(Supplier, java.util.concurrent.Executor)},
     * whose lambdas otherwise run on worker threads with empty MDC.
     *
     * @param supplier The original supplier
     * @param <T> Return type
     * @return A supplier that installs + restores MDC around the original
     */
    public static <T> Supplier<T> withMdcSupplier(final Supplier<T> supplier) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return supplier.get();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a single-argument {@link Consumer} so it restores the caller's MDC
     * context on whichever thread the callback runs.
     *
     * <p>Useful for RxJava subscribe callbacks and other async APIs that take a
     * plain {@code Consumer<T>} (e.g. onSuccess / onError lambdas). The captured
     * MDC is the one present at wrap time; pool threads are not polluted after
     * the consumer completes.</p>
     *
     * <p>Usage:
     * <pre>{@code
     * observable.subscribe(
     *     MdcPropagation.withMdcConsumer(result -> logger.info("done: {}", result)),
     *     MdcPropagation.withMdcConsumer(error -> logger.warn("failed", error))
     * );
     * }</pre>
     *
     * @param consumer The original consumer
     * @param <T> Input type
     * @return A consumer that installs + restores MDC around the original
     */
    public static <T> Consumer<T> withMdcConsumer(final Consumer<T> consumer) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return value -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                consumer.accept(value);
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Capture the current MDC context into a detached map.
     *
     * <p>Returns a defensive copy so callers can restore this snapshot later on
     * a different thread via {@link #runWith(Map, Runnable)}. Returns
     * {@code null} when the current MDC is null or empty so callers can treat
     * the absence of context as a simple no-op.</p>
     *
     * <p>Use this when the async callback is a non-standard functional
     * interface (e.g. RxJava {@code Consumer} in a 3-arg subscribe) and the
     * pre-wrapped {@link #withMdcConsumer(Consumer)} overload doesn't match.
     * Capture once at the boundary, then call {@link #runWith} inside the
     * callback body.</p>
     *
     * @return MDC snapshot, or {@code null} when the current MDC is empty
     */
    public static Map<String, String> capture() {
        final Map<String, String> ctx = MDC.getCopyOfContextMap();
        if (ctx == null || ctx.isEmpty()) {
            return null;
        }
        return new HashMap<>(ctx);
    }

    /**
     * Run an action with the given MDC snapshot installed, restoring the
     * thread's prior MDC when the action completes.
     *
     * <p>Companion to {@link #capture()}. If {@code snapshot} is {@code null}
     * the action is invoked without touching the current MDC.</p>
     *
     * <p>Usage:
     * <pre>{@code
     * final Map<String, String> snap = MdcPropagation.capture();
     * future.subscribe(result -> MdcPropagation.runWith(snap, () -> {
     *     logger.info("result received: {}", result);
     * }));
     * }</pre>
     *
     * @param snapshot MDC snapshot from {@link #capture()} (may be null)
     * @param action Action to run with the snapshot installed
     */
    public static void runWith(final Map<String, String> snapshot, final Runnable action) {
        if (snapshot == null) {
            action.run();
            return;
        }
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        try {
            MDC.setContextMap(snapshot);
            action.run();
        } finally {
            if (prior != null) {
                MDC.setContextMap(prior);
            } else {
                MDC.clear();
            }
        }
    }
}
