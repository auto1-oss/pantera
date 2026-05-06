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

import com.auto1.pantera.asto.PanteraIOException;
import java.io.IOException;

/**
 * Unchecked {@link Runnable}.
 *
 * @since 1.12
 */
public final class UncheckedRunnable implements Runnable {
    /**
     * Original runnable.
     */
    private final CheckedRunnable<? extends Exception> original;

    /**
     * Ctor.
     *
     * @param original Original runnable.
     */
    public UncheckedRunnable(final CheckedRunnable<? extends Exception> original) {
        this.original = original;
    }

    /**
     * New {@code UncheckedRunnable}.
     *
     * @param original Runnable, that can throw {@code IOException}
     * @param <E> An error
     * @return UncheckedRunnable
     */
    public static <E extends IOException> UncheckedRunnable newIoRunnable(
        final CheckedRunnable<E> original
    ) {
        return new UncheckedRunnable(original);
    }

    @Override
    public void run() {
        try {
            this.original.run();
        } catch (final Exception err) {
            throw new PanteraIOException(err);
        }
    }

    /**
     * Checked version of runnable.
     *
     * @param <E> Checked exception.
     * @since 1.12
     */
    @FunctionalInterface
    public interface CheckedRunnable<E extends Exception> {
        /**
         * Run action.
         *
         * @throws E An error.
         */
        void run() throws E;
    }
}
