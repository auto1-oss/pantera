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
package com.auto1.pantera.helm.misc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;

/**
 * Test for {@link SpaceInBeginning}.
 * @since 1.1.1
 */
final class SpaceInBeginningTest {
    @ParameterizedTest
    @CsvSource({
        "_entries:,0",
        "_  - maintainers,4",
        "_with_space_at_the_end   ,0",
        "_    four_space_both_sides    ,4"
    })
    void returnsPositionsOfSpaceAtBeginning(final String line, final int pos) {
        MatcherAssert.assertThat(
            new SpaceInBeginning(line.substring(1)).last(),
            new IsEqual<>(pos)
        );
    }
}
