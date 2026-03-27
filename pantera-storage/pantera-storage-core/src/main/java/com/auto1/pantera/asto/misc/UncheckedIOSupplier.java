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
import java.util.function.Supplier;

/**
 * Unchecked IO {@link Supplier}.
 * @param <T> Supplier type
 * @since 1.8
 */
public final class UncheckedIOSupplier<T> implements Supplier<T> {

    /**
     * Checked version.
     */
    private final UncheckedSupplier.CheckedSupplier<T, ? extends IOException> checked;

    /**
     * Ctor.
     * @param checked Checked func
     */
    public UncheckedIOSupplier(
        final UncheckedSupplier.CheckedSupplier<T, ? extends IOException> checked
    ) {
        this.checked = checked;
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public T get() {
        try {
            return this.checked.get();
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
    }
}
