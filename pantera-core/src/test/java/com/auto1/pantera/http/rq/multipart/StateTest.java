/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
