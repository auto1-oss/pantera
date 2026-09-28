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
package com.auto1.pantera.api.v1;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Repository deletes in progress on this node.
 *
 * <p>Removing a repository's data is one storage delete per key, so a large
 * repository takes longer than the API connection lives. The HTTP answer is
 * therefore bounded ({@link #answer}): the result when the removal ends
 * within the wait, otherwise "still running" (202). A second delete of a
 * name whose removal is still running is not started ({@link #start}).</p>
 *
 * @since 2.2.9
 */
final class RepositoryRemovals {

    /**
     * Removals in progress by repository name.
     */
    private final Map<String, CompletableFuture<Void>> running;

    /**
     * How long the HTTP answer waits for the removal.
     */
    private final Duration wait;

    /**
     * Ctor.
     * @param wait How long the HTTP answer waits for the removal
     */
    RepositoryRemovals(final Duration wait) {
        this.running = new ConcurrentHashMap<>();
        this.wait = wait;
    }

    /**
     * Start a removal unless one for the same name is still running.
     * @param name Repository name
     * @param removal Removal pipeline, started only when the name is free
     * @return The running removal, or empty when one was already running
     */
    Optional<CompletableFuture<Void>> start(
        final String name, final Supplier<? extends CompletionStage<Void>> removal
    ) {
        final CompletableFuture<Void> slot = new CompletableFuture<>();
        if (this.running.putIfAbsent(name, slot) != null) {
            return Optional.empty();
        }
        CompletionStage<Void> stage;
        try {
            stage = removal.get();
        } catch (final RuntimeException ex) {
            stage = CompletableFuture.failedFuture(ex);
        }
        stage.whenComplete((nothing, err) -> {
            this.running.remove(name, slot);
            if (err == null) {
                slot.complete(null);
            } else {
                slot.completeExceptionally(err);
            }
        });
        return Optional.of(slot);
    }

    /**
     * Whether a removal of this name is still running.
     * @param name Repository name
     * @return True while running
     */
    boolean inProgress(final String name) {
        return this.running.containsKey(name);
    }

    /**
     * The outcome to answer with: the removal's result when it ends within
     * the wait, otherwise pending. Never blocks and never cancels the
     * removal.
     * @param removal Running removal
     * @return Outcome, completed at the latest when the wait elapses
     */
    CompletableFuture<Outcome> answer(final CompletableFuture<Void> removal) {
        final CompletableFuture<Outcome> result = new CompletableFuture<>();
        removal.whenComplete(
            (nothing, err) -> result.complete(
                new Outcome(false, Optional.ofNullable(err).map(RepositoryRemovals::cause))
            )
        );
        result.completeOnTimeout(
            new Outcome(true, Optional.empty()), this.wait.toMillis(), TimeUnit.MILLISECONDS
        );
        return result;
    }

    /**
     * Unwrap completion wrappers.
     * @param err Failure
     * @return Root cause
     */
    private static Throwable cause(final Throwable err) {
        Throwable cur = err;
        while (cur instanceof CompletionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * What the HTTP answer reports.
     */
    static final class Outcome {

        /**
         * The removal was still running when the wait elapsed.
         */
        private final boolean waiting;

        /**
         * Failure of a finished removal.
         */
        private final Optional<Throwable> error;

        /**
         * Ctor.
         * @param waiting Still running
         * @param error Failure of a finished removal
         */
        Outcome(final boolean waiting, final Optional<Throwable> error) {
            this.waiting = waiting;
            this.error = error;
        }

        /**
         * Whether the removal was still running.
         * @return True when pending
         */
        boolean pending() {
            return this.waiting;
        }

        /**
         * Failure of a finished removal.
         * @return Root cause, empty on success or while pending
         */
        Optional<Throwable> failure() {
            return this.error;
        }
    }
}
