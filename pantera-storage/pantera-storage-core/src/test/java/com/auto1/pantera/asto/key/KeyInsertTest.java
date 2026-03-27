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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link KeyInsert}.
 *
 * @since 1.9.1
 */
final class KeyInsertTest {

    @Test
    void insertsPart() {
        final Key key = new Key.From("1", "2", "4");
        MatcherAssert.assertThat(
            new KeyInsert(key, "3", 2).string(),
            new IsEqual<>("1/2/3/4")
        );
    }

    @Test
    void insertsIndexOutOfBounds() {
        final Key key = new Key.From("1", "2");
        Assertions.assertThrows(
            IndexOutOfBoundsException.class,
            () -> new KeyInsert(key, "3", -1)
        );
    }
}
