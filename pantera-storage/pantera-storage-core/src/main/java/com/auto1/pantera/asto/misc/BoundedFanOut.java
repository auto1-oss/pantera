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
package com.auto1.pantera.asto.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runs an asynchronous action for every item of a collection, at most
 * {@code width} of them at a time. A slot takes the next item when its
 * action completes, so neither a chain of stages as long as the collection
 * nor an unbounded burst of requests is built.
 *
 * <p>After the first failure no further item is started; the result fails
 * with that failure once the actions in flight are over.</p>
 *
 * @param <T> Item type
 * @since 2.2.14
 */
public final class BoundedFanOut<T> {

    /**
     * Actions in flight at most.
     */
    private final int width;

    /**
     * Action.
     */
    private final Function<? super T, ? extends CompletionStage<?>> action;

    /**
     * Ctor.
     * @param width Actions in flight at most
     * @param action Action to run for an item
     */
    public BoundedFanOut(
        final int width,
        final Function<? super T, ? extends CompletionStage<?>> action
    ) {
        if (width < 1) {
            throw new IllegalArgumentException(
                String.format("Fan-out width must be positive: %d", width)
            );
        }
        this.width = width;
        this.action = action;
    }

    /**
     * Run the action for every item.
     * @param items Items
     * @return Completion; failed with the first failure of an action
     */
    public CompletableFuture<Void> run(final Collection<? extends T> items) {
        final int slots = Math.max(1, Math.min(this.width, items.size()));
        final Run<T> run = new Run<>(new ArrayList<T>(items).iterator(), this.action, slots);
        for (int slot = 0; slot < slots; slot += 1) {
            run.pump();
        }
        return run.result();
    }

    /**
     * State of one run.
     * @param <T> Item type
     * @since 2.2.14
     */
    private static final class Run<T> {

        /**
         * Items not started yet.
         */
        private final Iterator<T> items;

        /**
         * Action.
         */
        private final Function<? super T, ? extends CompletionStage<?>> action;

        /**
         * Slots still working.
         */
        private final AtomicInteger open;

        /**
         * First failure.
         */
        private final AtomicReference<Throwable> failure;

        /**
         * Result.
         */
        private final CompletableFuture<Void> done;

        /**
         * Ctor.
         * @param items Items
         * @param action Action
         * @param slots Number of slots that will work on the items
         */
        Run(
            final Iterator<T> items,
            final Function<? super T, ? extends CompletionStage<?>> action,
            final int slots
        ) {
            this.items = items;
            this.action = action;
            this.open = new AtomicInteger(slots);
            this.failure = new AtomicReference<>();
            this.done = new CompletableFuture<>();
        }

        /**
         * Result of the run.
         * @return Completion
         */
        CompletableFuture<Void> result() {
            return this.done;
        }

        /**
         * Work of one slot: start items one after another, returning while
         * an action is pending; its completion resumes the slot.
         */
        void pump() {
            boolean pending = false;
            while (!pending) {
                final Optional<T> item = this.next();
                if (item.isEmpty()) {
                    this.close();
                    break;
                }
                final CompletableFuture<?> stage = this.start(item.get());
                if (stage.isDone()) {
                    stage.whenComplete((ignored, err) -> this.note(err));
                } else {
                    pending = true;
                    stage.whenComplete(
                        (ignored, err) -> {
                            this.note(err);
                            this.pump();
                        }
                    );
                }
            }
        }

        /**
         * Start the action for an item.
         * @param item Item
         * @return Its completion; failed if the action threw
         */
        private CompletableFuture<?> start(final T item) {
            CompletableFuture<?> stage;
            try {
                stage = this.action.apply(item).toCompletableFuture();
            } catch (final RuntimeException err) {
                stage = CompletableFuture.failedFuture(err);
            }
            return stage;
        }

        /**
         * Next item to start; none once the items are used up or an action
         * failed.
         * @return Item
         */
        private Optional<T> next() {
            synchronized (this.items) {
                final Optional<T> item;
                if (this.failure.get() == null && this.items.hasNext()) {
                    item = Optional.of(this.items.next());
                } else {
                    item = Optional.empty();
                }
                return item;
            }
        }

        /**
         * Remember the first failure.
         * @param err Failure or null
         */
        private void note(final Throwable err) {
            if (err != null) {
                this.failure.compareAndSet(null, err);
            }
        }

        /**
         * A slot has nothing left to do; the last one completes the run.
         */
        private void close() {
            if (this.open.decrementAndGet() == 0) {
                final Throwable err = this.failure.get();
                if (err == null) {
                    this.done.complete(null);
                } else {
                    this.done.completeExceptionally(err);
                }
            }
        }
    }
}
