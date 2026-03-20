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
package com.auto1.pantera.asto;

import java.nio.ByteBuffer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link Remaining}.
 * @since 0.32
 */
public final class RemainingTest {

    @Test
    public void readTwiceWithRestoreStrategy() throws Exception {
        final ByteBuffer buf = ByteBuffer.allocate(32);
        final byte[] array = new byte[]{1, 2, 3, 4};
        buf.put(array);
        buf.flip();
        MatcherAssert.assertThat(
            new Remaining(buf, true).bytes(), new IsEqual<>(array)
        );
        MatcherAssert.assertThat(
            new Remaining(buf, true).bytes(), new IsEqual<>(array)
        );
    }
}
