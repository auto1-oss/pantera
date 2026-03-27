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

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Executor wrapper that propagates MDC (trace context) to async operations.
 * 
 * <p>SLF4J's MDC is ThreadLocal-based, so it doesn't automatically propagate to
 * async operations (CompletableFuture, ExecutorService, etc.). This class ensures
 * that trace.id and other MDC values are copied to async threads.
 * 
 * <p>Usage examples:
 * <pre>{@code
 * // Wrap CompletableFuture.runAsync
 * CompletableFuture.runAsync(
 *     TraceContextExecutor.wrap(() -> {
 *         // trace.id is available here
 *         logger.info("Async operation");
 *     })
 * );
 * 
 * // Wrap CompletableFuture.supplyAsync
 * CompletableFuture.supplyAsync(
 *     TraceContextExecutor.wrapSupplier(() -> {
 *         // trace.id is available here
 *         return computeResult();
 *     })
 * );
 * 
 * // Wrap ExecutorService
 * ExecutorService executor = Executors.newFixedThreadPool(10);
 * ExecutorService tracingExecutor = TraceContextExecutor.wrap(executor);
 * tracingExecutor.submit(() -> {
 *     // trace.id is available here
 *     logger.info("Task executed");
 * });
 * }</pre>
 * 
 * @since 1.18.24
 */
public final class TraceContextExecutor {

    /**
     * Private constructor - utility class.
     */
    private TraceContextExecutor() {
    }

    /**
     * Wrap a Runnable to propagate MDC context.
     * @param runnable Original runnable
     * @return Wrapped runnable with MDC propagation
     */
    public static Runnable wrap(final Runnable runnable) {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a Callable to propagate MDC context.
     * @param callable Original callable
     * @param <T> Return type
     * @return Wrapped callable with MDC propagation
     */
    public static <T> Callable<T> wrap(final Callable<T> callable) {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                return callable.call();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a Supplier to propagate MDC context.
     * @param supplier Original supplier
     * @param <T> Return type
     * @return Wrapped supplier with MDC propagation
     */
    public static <T> Supplier<T> wrapSupplier(final Supplier<T> supplier) {
        final Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                return supplier.get();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap an ExecutorService to propagate MDC context to all submitted tasks.
     * @param executor Original executor
     * @return Wrapped executor with MDC propagation
     */
    public static ExecutorService wrap(final ExecutorService executor) {
        return new MdcAwareExecutorService(executor);
    }

    /**
     * Wrap an Executor to propagate MDC context to all submitted tasks.
     * @param executor Original executor
     * @return Wrapped executor with MDC propagation
     */
    public static Executor wrap(final Executor executor) {
        return new MdcAwareExecutor(executor);
    }

    /**
     * MDC-aware Executor wrapper.
     */
    private static final class MdcAwareExecutor implements Executor {
        private final Executor delegate;

        MdcAwareExecutor(final Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(final Runnable command) {
            this.delegate.execute(TraceContextExecutor.wrap(command));
        }
    }

    /**
     * MDC-aware ExecutorService wrapper.
     */
    private static final class MdcAwareExecutorService implements ExecutorService {
        private final ExecutorService delegate;

        MdcAwareExecutorService(final ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(final Runnable command) {
            this.delegate.execute(TraceContextExecutor.wrap(command));
        }

        @Override
        public void shutdown() {
            this.delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
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
        public boolean awaitTermination(final long timeout, final java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
            return this.delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(final Callable<T> task) {
            return this.delegate.submit(TraceContextExecutor.wrap(task));
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(final Runnable task, final T result) {
            return this.delegate.submit(TraceContextExecutor.wrap(task), result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(final Runnable task) {
            return this.delegate.submit(TraceContextExecutor.wrap(task));
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
            final java.util.Collection<? extends Callable<T>> tasks
        ) throws InterruptedException {
            return this.delegate.invokeAll(wrapCallables(tasks));
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
            final java.util.Collection<? extends Callable<T>> tasks,
            final long timeout,
            final java.util.concurrent.TimeUnit unit
        ) throws InterruptedException {
            return this.delegate.invokeAll(wrapCallables(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(final java.util.Collection<? extends Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
            return this.delegate.invokeAny(wrapCallables(tasks));
        }

        @Override
        public <T> T invokeAny(
            final java.util.Collection<? extends Callable<T>> tasks,
            final long timeout,
            final java.util.concurrent.TimeUnit unit
        ) throws InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
            return this.delegate.invokeAny(wrapCallables(tasks), timeout, unit);
        }

        private static <T> java.util.Collection<Callable<T>> wrapCallables(
            final java.util.Collection<? extends Callable<T>> tasks
        ) {
            final java.util.List<Callable<T>> wrapped = new java.util.ArrayList<>(tasks.size());
            for (final Callable<T> task : tasks) {
                wrapped.add(TraceContextExecutor.wrap(task));
            }
            return wrapped;
        }
    }
}

