/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static <E extends IOException> UncheckedRunnable newIoRunnable(
        final CheckedRunnable<E> original
    ) {
        return new UncheckedRunnable(original);
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
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
