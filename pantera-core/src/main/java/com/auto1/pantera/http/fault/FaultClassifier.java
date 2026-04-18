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
package com.auto1.pantera.http.fault;

import com.auto1.pantera.asto.ValueNotFoundException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Translates a {@link Throwable} that escaped a slice into a {@link Fault} variant.
 *
 * <p>Used exclusively by {@code .exceptionally(...)} handlers as a last line of
 * defence — normal fault signalling is via {@link Result.Err}. See §9 of
 * {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>{@link CompletionException} wrappers are unwrapped before classification so
 * that an {@link IOException} nested inside a completion-stage pipeline still
 * lands on the {@link Fault.Internal} branch, not the default branch.
 *
 * @since 2.2.0
 */
public final class FaultClassifier {

    /** Retry hint attached to queue-full {@link Fault.Overload} faults. */
    private static final Duration QUEUE_FULL_RETRY_AFTER = Duration.ofSeconds(1);

    /** Sentinel budget used when we have no real timeout to attach. */
    private static final Duration UNKNOWN_BUDGET = Duration.ZERO;

    private FaultClassifier() {
    }

    /**
     * Classify a throwable into a {@link Fault} variant.
     *
     * @param throwable The throwable caught on a request path. May be a
     *                  {@link CompletionException} wrapping the real cause.
     * @param where     A short, stable label identifying the call site that
     *                  caught the throwable (e.g. {@code "proxy-fanout"},
     *                  {@code "storage-read"}). Used for debugging and
     *                  attached to the resulting {@link Fault}.
     * @return The corresponding {@link Fault}. Never null.
     */
    public static Fault classify(final Throwable throwable, final String where) {
        final Throwable cause = unwrap(throwable);
        if (cause instanceof TimeoutException) {
            return new Fault.Deadline(UNKNOWN_BUDGET, where);
        }
        if (cause instanceof ValueNotFoundException vnf) {
            return new Fault.StorageUnavailable(vnf, vnf.getMessage());
        }
        if (cause instanceof ConnectException conn) {
            return new Fault.Internal(conn, where);
        }
        if (cause instanceof IOException ioe) {
            return new Fault.Internal(ioe, where);
        }
        if (cause instanceof IllegalStateException ise
            && "Queue full".equals(ise.getMessage())) {
            return new Fault.Overload("event-queue", QUEUE_FULL_RETRY_AFTER);
        }
        return new Fault.Internal(cause, where);
    }

    /**
     * Unwrap {@link CompletionException} layers so the real cause is visible
     * to {@link #classify}.
     *
     * @param throwable Incoming throwable. If {@code null}, the same is returned
     *                  (the caller is responsible for handling that case).
     * @return The innermost non-{@link CompletionException} cause, or the input
     *         itself if no unwrapping was needed.
     */
    static Throwable unwrap(final Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null
            && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
