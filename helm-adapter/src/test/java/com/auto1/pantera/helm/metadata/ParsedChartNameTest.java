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
package com.auto1.pantera.helm.metadata;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ParsedChartName}.
 * @since 0.3
 */
final class ParsedChartNameTest {
    @ParameterizedTest
    @ValueSource(strings = {"name:", " name_with_space_before:", " space_both: "})
    void returnsValidForCorrectName(final String name) {
        MatcherAssert.assertThat(
            new ParsedChartName(name).valid(),
            new IsEqual<>(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"without_colon", " - starts_with_dash:", "entries:"})
    void returnsNotValidForMalformedName(final String name) {
        MatcherAssert.assertThat(
            new ParsedChartName(name).valid(),
            new IsEqual<>(false)
        );
    }
}
