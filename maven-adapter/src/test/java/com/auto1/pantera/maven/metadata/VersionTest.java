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
package com.auto1.pantera.maven.metadata;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link Version}.
 * Uses Maven's ComparableVersion which returns any negative/positive value,
 * not necessarily -1/+1. Tests check signum of result.
 * @since 0.5
 */
class VersionTest {

    @CsvSource({
        "1,1,0",
        "1,2,-1",
        "2,1,1",
        "0.2,0.20.1,-1",
        "1.0,1.1-SNAPSHOT,-1",
        "2.0-SNAPSHOT,1.1,1",
        "0.1-SNAPSHOT,0.3-SNAPSHOT,-1",
        "1.0.1,0.1,1",
        "1.1-alpha-2,1.1,-1",
        "1.1-alpha-2,1.1-alpha-3,-1"
    })
    @ParameterizedTest
    void comparesSimpleVersions(final String first, final String second, final int res) {
        MatcherAssert.assertThat(
            Integer.signum(new Version(first).compareTo(new Version(second))),
            new IsEqual<>(res)
        );
    }

}
