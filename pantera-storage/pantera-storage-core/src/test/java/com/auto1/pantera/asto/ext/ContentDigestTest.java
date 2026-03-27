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
package com.auto1.pantera.asto.ext;

import com.auto1.pantera.asto.Content;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link ContentDigest}.
 *
 * @since 0.22
 */
final class ContentDigestTest {

    @Test
    void calculatesHex() throws Exception {
        MatcherAssert.assertThat(
            new ContentDigest(
                new Content.OneTime(
                    new Content.From(
                        new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe}
                    )
                ),
                Digests.SHA256
            ).hex().toCompletableFuture().get(),
            new IsEqual<>("65ab12a8ff3263fbc257e5ddf0aa563c64573d0bab1f1115b9b107834cfa6971")
        );
    }
}
