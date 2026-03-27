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
package com.auto1.pantera.docker.composite;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * Tests for {@link MultiReadRepo}.
 *
 * @since 0.3
 */
final class MultiReadRepoTest {

    @Test
    void createsMultiReadLayers() {
        MatcherAssert.assertThat(
            new MultiReadRepo("one", new ArrayList<>()).layers(),
            new IsInstanceOf(MultiReadLayers.class)
        );
    }

    @Test
    void createsMultiReadManifests() {
        MatcherAssert.assertThat(
            new MultiReadRepo("two", new ArrayList<>()).manifests(),
            new IsInstanceOf(MultiReadManifests.class)
        );
    }
}
