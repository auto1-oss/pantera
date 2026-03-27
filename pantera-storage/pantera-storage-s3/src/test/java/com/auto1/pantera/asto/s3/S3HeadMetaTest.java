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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Meta;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Test case for {@link S3HeadMeta}.
 * @since 0.1
 */
final class S3HeadMetaTest {

    @Test
    void readSize() {
        final long len = 1024;
        MatcherAssert.assertThat(
            new S3HeadMeta(
                HeadObjectResponse.builder()
                    .contentLength(len)
                    .eTag("empty")
                    .build()
            ).read(Meta.OP_SIZE).orElseThrow(IllegalStateException::new),
            new IsEqual<>(len)
        );
    }

    @Test
    void readHash() {
        final String hash = "abc";
        MatcherAssert.assertThat(
            new S3HeadMeta(
                HeadObjectResponse.builder()
                    .contentLength(0L)
                    .eTag(hash)
                    .build()
            ).read(Meta.OP_MD5).orElseThrow(IllegalStateException::new),
            new IsEqual<>(hash)
        );
    }
}
