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
package com.auto1.pantera.debian.misc;

import com.auto1.pantera.asto.test.TestResource;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SizeAndDigest}.
 * @since 0.6
 */
class SizeAndDigestTest {

    @Test
    void calcsSizeAndDigest() {
        MatcherAssert.assertThat(
            new SizeAndDigest().apply(new TestResource("Packages.gz").asInputStream()),
            new IsEqual<>(
                new ImmutablePair<>(
                    2564L, "c1cfc96b4ca50645c57e10b65fcc89fd1b2b79eb495c9fa035613af7ff97dbff"
                )
            )
        );
    }

}
