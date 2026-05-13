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
package com.auto1.pantera.rpm.misc;

import java.util.function.Function;

/**
 * Unchecked {@link java.util.function.Function}.
 * @param <T> Function type
 * @param <R> Function return type
 * @param <E> Error type
 * @since 0.8
 */
public final class UncheckedFunc<T, R, E extends Throwable> implements Function<T, R> {

    /**
     * Checked version.
     */
    private final Checked<T, R, E> checked;

    /**
     * Ctor.
     * @param checked Checked func
     */
    public UncheckedFunc(final UncheckedFunc.Checked<T, R, E> checked) {
        this.checked = checked;
    }

    @Override
    public R apply(final T val) {
        try {
            return this.checked.apply(val);
        } catch (final Throwable err) {
            throw new IllegalStateException(err);
        }
    }

    /**
     * Checked version of consumer.
     * @param <T> Consumer type
     * @param <R> Return type
     * @param <E> Error type
     * @since 0.8
     */
    @FunctionalInterface
    public interface Checked<T, R, E extends Throwable> {

        /**
         * Apply value.
         * @param value Value to accept
         * @return Result
         * @throws E On error
         */
        R apply(T value) throws E;
    }
}
