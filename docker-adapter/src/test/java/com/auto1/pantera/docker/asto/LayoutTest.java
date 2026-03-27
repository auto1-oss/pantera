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
package com.auto1.pantera.docker.asto;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link Layout}.
 */
public final class LayoutTest {

    @Test
    public void buildsRepositories() {
        MatcherAssert.assertThat(
            Layout.repositories().string(),
            new IsEqual<>("repositories")
        );
    }

    @Test
    public void buildsTags() {
        MatcherAssert.assertThat(
            Layout.tags("my-alpine").string(),
            new IsEqual<>("repositories/my-alpine/_manifests/tags")
        );
    }
}
