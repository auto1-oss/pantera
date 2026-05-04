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
package com.auto1.pantera.prefetch;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link Coordinate}.
 * @since 2.2.0
 */
class CoordinateTest {

    @Test
    void mavenPath() {
        MatcherAssert.assertThat(
            Coordinate.maven("com.google.guava", "guava", "33.5.0-jre").path(),
            new IsEqual<>("com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar")
        );
    }

    @Test
    void npmScopedPath() {
        MatcherAssert.assertThat(
            Coordinate.npm("@types/node", "20.10.0").path(),
            new IsEqual<>("@types/node/-/node-20.10.0.tgz")
        );
    }

    @Test
    void npmUnscopedPath() {
        MatcherAssert.assertThat(
            Coordinate.npm("react", "18.0.0").path(),
            new IsEqual<>("react/-/react-18.0.0.tgz")
        );
    }
}
