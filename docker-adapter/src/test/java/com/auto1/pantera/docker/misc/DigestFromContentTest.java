/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import org.apache.commons.codec.digest.DigestUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link DigestFromContent}.
 * @since 0.2
 */
class DigestFromContentTest {

    @Test
    void calculatesHexCorrectly() {
        final byte[] data = "abc123".getBytes();
        MatcherAssert.assertThat(
            new DigestFromContent(new Content.From(data))
                .digest().toCompletableFuture().join().hex(),
            new IsEqual<>(DigestUtils.sha256Hex(data))
        );
    }

}
