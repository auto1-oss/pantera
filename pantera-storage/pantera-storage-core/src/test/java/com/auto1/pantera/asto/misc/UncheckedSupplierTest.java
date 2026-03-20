/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.misc;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.PanteraIOException;
import java.io.IOException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UncheckedSupplier} and {@link UncheckedIOSupplier}.
 * @since 1.8
 */
class UncheckedSupplierTest {

    @Test
    void throwsPanteraException() {
        final Exception error = new Exception("Error");
        final Exception res = Assertions.assertThrows(
            PanteraException.class,
            () -> new UncheckedSupplier<>(() -> { throw error; }).get()
        );
        MatcherAssert.assertThat(
            res.getCause(),
            new IsEqual<>(error)
        );
    }

    @Test
    void throwsPanteraIOException() {
        final IOException error = new IOException("IO error");
        final Exception res = Assertions.assertThrows(
            PanteraIOException.class,
            () -> new UncheckedIOSupplier<>(() -> { throw error; }).get()
        );
        MatcherAssert.assertThat(
            res.getCause(),
            new IsEqual<>(error)
        );
    }

}
