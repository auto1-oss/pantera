/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.misc;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test cases for {@link RandomFreePort}.
 * @since 0.18
 */
final class RandomFreePortTest {
    @Test
    void returnsFreePort() {
        MatcherAssert.assertThat(
            RandomFreePort.get(),
            new IsInstanceOf(Integer.class)
        );
    }
}
