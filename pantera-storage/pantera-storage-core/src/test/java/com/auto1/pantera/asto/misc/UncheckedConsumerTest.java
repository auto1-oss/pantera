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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UncheckedConsumer} and {@link UncheckedIOConsumer}.
 * @since 1.1
 */
class UncheckedConsumerTest {

    @Test
    void throwsPanteraException() {
        final Exception error = new Exception("Error");
        final Exception res = Assertions.assertThrows(
            PanteraException.class,
            () -> new UncheckedConsumer<>(ignored -> { throw error; }).accept("ignored")
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
            () -> new UncheckedIOConsumer<>(ignored -> { throw error; }).accept("nothing")
        );
        MatcherAssert.assertThat(
            res.getCause(),
            new IsEqual<>(error)
        );
    }

}
