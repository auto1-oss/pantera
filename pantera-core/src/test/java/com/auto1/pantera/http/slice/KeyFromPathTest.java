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
package com.auto1.pantera.http.slice;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link KeyFromPath}.
 *
 * @since 0.6
 */
final class KeyFromPathTest {

    @Test
    void removesLeadingSlashes() {
        MatcherAssert.assertThat(
            new KeyFromPath("/foo/bar").string(),
            new IsEqual<>("foo/bar")
        );
    }

    @Test
    void usesRelativePathsSlashes() {
        final String rel = "one/two";
        MatcherAssert.assertThat(
            new KeyFromPath(rel).string(),
            new IsEqual<>(rel)
        );
    }
}
