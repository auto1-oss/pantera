/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
