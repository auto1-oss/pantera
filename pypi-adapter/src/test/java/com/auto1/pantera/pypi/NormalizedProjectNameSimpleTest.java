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
package com.auto1.pantera.pypi;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link NormalizedProjectName.Simple}.
 * @since 0.6
 */
class NormalizedProjectNameSimpleTest {

    @Test
    void throwsExceptionOnInvalidName() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new NormalizedProjectName.Simple("one/two/three").value()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "superProject,superproject",
        "my-super-project,my-super-project",
        "One._Two._Three,one-two-three",
        "agent--007,agent-007"
    })
    void normalizesNames(final String name, final String normalized) {
        MatcherAssert.assertThat(
            new NormalizedProjectName.Simple(name).value(),
            new IsEqual<>(normalized)
        );
    }

}
