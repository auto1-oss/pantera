/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RsStatus}.
 */
class RsStatusTest {

    @Test
    void shouldResolvePreconditionFailed() {
        MatcherAssert.assertThat(
            RsStatus.byCode(412),
            new IsEqual<>(RsStatus.PRECONDITION_FAILED)
        );
    }
}
