/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
