/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.misc;

import com.auto1.pantera.ArtipieException;
import com.auto1.pantera.asto.ArtipieIOException;
import java.io.IOException;

/**
 * Scalar that throws {@link ArtipieException} on error.
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
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public T value() {
        try {
            return this.origin.value();
        } catch (final IOException ex) {
            throw new ArtipieIOException(ex);
        }
    }
}
