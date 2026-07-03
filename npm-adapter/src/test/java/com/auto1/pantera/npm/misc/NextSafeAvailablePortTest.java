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
package com.auto1.pantera.npm.misc;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test cases for {@link NextSafeAvailablePort}.
 * @since 0.9
 */
final class NextSafeAvailablePortTest {

    @ParameterizedTest
    @ValueSource(ints = {1_023, 49_152})
    void failsByInvalidPort(final int port) {
        final Throwable thrown =
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NextSafeAvailablePort(port).value()
            );
        MatcherAssert.assertThat(
            thrown.getMessage(),
            new IsEqual<>(
                String.format("Invalid start port: %s", port)
            )
        );
    }

    @Test
    void getNextValue() {
        MatcherAssert.assertThat(
            new NextSafeAvailablePort().value(),
            Matchers.allOf(
                Matchers.greaterThan(1023),
                Matchers.lessThan(49_152)
            )
        );
    }
}
