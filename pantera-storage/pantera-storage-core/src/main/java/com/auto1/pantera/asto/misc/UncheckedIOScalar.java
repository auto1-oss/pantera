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
import com.auto1.pantera.asto.PanteraIOException;
import java.io.IOException;

/**
 * Scalar that throws {@link PanteraException} on error.
 * @param <T> Return value type
 * @since 1.3
 */
public final class UncheckedIOScalar<T> implements Scalar<T> {

    /**
     * Original origin.
     */
    private final UncheckedScalar.Checked<T, ? extends IOException> origin;

    /**
     * Ctor.
     * @param origin Encapsulated origin
     */
    public UncheckedIOScalar(final UncheckedScalar.Checked<T, ? extends IOException> origin) {
        this.origin = origin;
    }

    @Override
    public T value() {
        try {
            return this.origin.value();
        } catch (final IOException ex) {
            throw new PanteraIOException(ex);
        }
    }
}
