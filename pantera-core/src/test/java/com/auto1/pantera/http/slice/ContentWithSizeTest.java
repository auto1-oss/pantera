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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ContentLength;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ContentWithSize}.
 * @since 0.18
 */
final class ContentWithSizeTest {

    @Test
    void parsesHeaderValue() {
        final long length = 100L;
        MatcherAssert.assertThat(
            new ContentWithSize(Content.EMPTY, Headers.from(new ContentLength(length))).size()
                .orElse(0L),
            new IsEqual<>(length)
        );
    }
}
