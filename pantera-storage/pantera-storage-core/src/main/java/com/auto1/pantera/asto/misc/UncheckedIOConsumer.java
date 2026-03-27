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
import java.util.function.Consumer;

/**
 * Unchecked IO {@link Consumer}.
 * @param <T> Consumer type
 * @since 1.1
 */
public final class UncheckedIOConsumer<T> implements Consumer<T> {

    /**
     * Checked version.
     */
    private final UncheckedConsumer.Checked<T, ? extends IOException> checked;

    /**
     * Ctor.
     * @param checked Checked func
     */
    public UncheckedIOConsumer(final UncheckedConsumer.Checked<T, ? extends IOException> checked) {
        this.checked = checked;
    }

    @Override
    public void accept(final T val) {
        try {
            this.checked.accept(val);
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
    }
}
