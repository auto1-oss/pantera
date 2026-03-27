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
package com.auto1.pantera.asto.ext;

import com.auto1.pantera.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link KeyLastPart}.
 * @since 0.24
 */
class KeyLastPartTest {

    @ParameterizedTest
    @CsvSource({
        "abc/def/some_file.txt,some_file.txt",
        "a/b/c/e/c,c",
        "one,one",
        "four/,four",
        "'',''"
    })
    void normalisesNames(final String key, final String expected) {
        MatcherAssert.assertThat(
            new KeyLastPart(new Key.From(key)).get(),
            new IsEqual<>(expected)
        );
    }

}
