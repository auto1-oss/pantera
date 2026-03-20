/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
