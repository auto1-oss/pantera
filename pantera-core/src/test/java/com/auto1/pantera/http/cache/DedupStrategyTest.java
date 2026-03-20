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
package com.auto1.pantera.http.cache;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link DedupStrategy}.
 */
class DedupStrategyTest {

    @Test
    void hasThreeValues() {
        assertThat(
            DedupStrategy.values(),
            arrayContaining(DedupStrategy.NONE, DedupStrategy.STORAGE, DedupStrategy.SIGNAL)
        );
    }

    @Test
    void valueOfWorks() {
        assertThat(DedupStrategy.valueOf("SIGNAL"), equalTo(DedupStrategy.SIGNAL));
        assertThat(DedupStrategy.valueOf("NONE"), equalTo(DedupStrategy.NONE));
        assertThat(DedupStrategy.valueOf("STORAGE"), equalTo(DedupStrategy.STORAGE));
    }
}
