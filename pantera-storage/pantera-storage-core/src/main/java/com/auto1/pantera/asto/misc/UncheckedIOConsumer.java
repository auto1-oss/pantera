/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
