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
package com.auto1.pantera.helm.http;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PushChartSlice#bufsToByteArr(List)}.
 *
 * @since 0.1
 */
public class BufsToByteArrTest {

    @Test
    public void copyIsCorrect() {
        final String actual = new String(
            PushChartSlice.bufsToByteArr(
                Arrays.asList(
                    ByteBuffer.wrap("123".getBytes()),
                    ByteBuffer.wrap("456".getBytes()),
                    ByteBuffer.wrap("789".getBytes())
                )
            )
        );
        MatcherAssert.assertThat(
            actual,
            new IsEqual<>("123456789")
        );
    }
}
