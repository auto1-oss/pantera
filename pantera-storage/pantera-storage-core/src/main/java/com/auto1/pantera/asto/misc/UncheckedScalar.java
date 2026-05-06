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

import com.auto1.pantera.PanteraException;

/**
 * Scalar that throws {@link com.auto1.pantera.PanteraException} on error.
 * @param <T> Return value type
 * @param <E> Error type
 * @since 1.3
 */
public final class UncheckedScalar<T, E extends Exception> implements Scalar<T> {

    /**
     * Original origin.
     */
    private final Checked<T, E> origin;

    /**
     * Ctor.
     * @param origin Encapsulated origin
     */
    public UncheckedScalar(final Checked<T, E> origin) {
        this.origin = origin;
    }

    @Override
    public T value() {
        try {
            return this.origin.value();
        } catch (final Exception ex) {
            throw new PanteraException(ex);
        }
    }

    /**
     * Checked version of scalar.
     * @param <R> Return type
     * @param <E> Error type
     * @since 1.1
     */
    @FunctionalInterface
    public interface Checked<R, E extends Exception> {

        /**
         * Return value.
         * @return Result
         * @throws E On error
         */
        R value() throws E;
    }
}
