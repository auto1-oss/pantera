/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
