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
