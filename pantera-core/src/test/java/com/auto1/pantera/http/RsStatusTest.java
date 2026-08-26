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

    @Test
    void mapsTheStatusesUpstreamsActuallySend() {
        for (final int code : new int[]{402, 406, 410, 414, 415, 422, 428, 451, 507}) {
            MatcherAssert.assertThat(
                String.format("status %d must map, upstreams send it", code),
                RsStatus.byCode(code).code(), new IsEqual<>(code)
            );
        }
    }
}
