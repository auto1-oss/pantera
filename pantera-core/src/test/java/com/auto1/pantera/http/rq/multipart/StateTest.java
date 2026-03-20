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
package com.auto1.pantera.http.rq.multipart;

import java.nio.ByteBuffer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link State}.
 * @since 1.1
 */
final class StateTest {
    @Test
    void initOnlyOnFirstCall() {
        final State state = new State();
        MatcherAssert.assertThat("should be in init state", state.isInit(), Matchers.is(true));
        state.patch(ByteBuffer.allocate(0), false);
        MatcherAssert.assertThat("should be not in init state", state.isInit(), Matchers.is(false));
    }
}
