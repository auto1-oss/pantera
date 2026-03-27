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
import java.util.function.Supplier;

/**
 * Supplier to wrap checked supplier throwing checked exception
 * with unchecked one.
 * @param <T> Supplier type
 * @since 1.8
 */
public final class UncheckedSupplier<T> implements Supplier<T> {

    /**
     * Supplier which throws checked exceptions.
     */
    private final CheckedSupplier<? extends T, ? extends Exception> checked;

    /**
     * Wrap checked supplier with unchecked.
     * @param checked Checked supplier
     */
    public UncheckedSupplier(final CheckedSupplier<T, ? extends Exception> checked) {
        this.checked = checked;
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public T get() {
        try {
            return this.checked.get();
        } catch (final Exception err) {
            throw new PanteraException(err);
        }
    }

    /**
     * Checked supplier which throws exception.
     * @param <T> Supplier type
     * @param <E> Exception type
     * @since 1.0
     */
    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {

        /**
         * Get value or throw exception.
         * @return Value
         * @throws Exception of type E
         */
        T get() throws E;
    }
}
