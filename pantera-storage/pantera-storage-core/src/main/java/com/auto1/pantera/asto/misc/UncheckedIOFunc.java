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
import java.util.function.Function;

/**
 * Unchecked IO {@link Function}.
 * @param <T> Function type
 * @param <R> Function return type
 * @since 1.1
 */
public final class UncheckedIOFunc<T, R> implements Function<T, R> {

    /**
     * Checked version.
     */
    private final UncheckedFunc.Checked<T, R, ? extends IOException> checked;

    /**
     * Ctor.
     * @param checked Checked func
     */
    public UncheckedIOFunc(final UncheckedFunc.Checked<T, R, ? extends IOException> checked) {
        this.checked = checked;
    }

    @Override
    public R apply(final T val) {
        try {
            return this.checked.apply(val);
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
    }
}
