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
package com.auto1.pantera.asto.key;

import com.auto1.pantera.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link KeyExcludeLast}.
 *
 * @since 1.9.1
 */
final class KeyExcludeLastTest {

    @Test
    void excludesLastPart() {
        final Key key = new Key.From("1", "2", "1");
        MatcherAssert.assertThat(
            new KeyExcludeLast(key, "1").string(),
            new IsEqual<>("1/2")
        );
    }

    @Test
    void excludesWhenPartIsNotAtEnd() {
        final Key key = new Key.From("one", "two", "three");
        MatcherAssert.assertThat(
            new KeyExcludeLast(key, "two").string(),
            new IsEqual<>("one/three")
        );
    }

    @Test
    void excludesNonExistingPart() {
        final Key key = new Key.From("3", "4");
        MatcherAssert.assertThat(
            new KeyExcludeLast(key, "5").string(),
            new IsEqual<>("3/4")
        );
    }
}
