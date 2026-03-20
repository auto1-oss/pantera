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
package com.auto1.pantera.composer;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Name}.
 *
 * @since 0.1
 */
class NameTest {

    @Test
    void shouldGenerateKey() {
        MatcherAssert.assertThat(
            new Name("vendor/package").key().string(),
            Matchers.is("p2/vendor/package.json")
        );
    }
}
