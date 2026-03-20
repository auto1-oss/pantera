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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.http.blobs.BlobsRequest;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link BlobsRequest}.
 */
class BlobEntityRequestTest {

    @Test
    void shouldReadName() {
        final String name = "my-repo";
        MatcherAssert.assertThat(
            BlobsRequest.from(
                new RequestLine(
                    RqMethod.HEAD, String.format("/v2/%s/blobs/sha256:098", name)
                )
            ).name(),
            Matchers.is(name)
        );
    }

    @Test
    void shouldReadDigest() {
        final String digest = "sha256:abc123";
        MatcherAssert.assertThat(
            BlobsRequest.from(
                new RequestLine(
                    RqMethod.GET, String.format("/v2/some-repo/blobs/%s", digest)
                )
            ).digest().string(),
            Matchers.is(digest)
        );
    }

    @Test
    void shouldReadCompositeName() {
        final String name = "zero-one/two.three/four_five";
        MatcherAssert.assertThat(
            BlobsRequest.from(
                new RequestLine(
                    RqMethod.HEAD, String.format("/v2/%s/blobs/sha256:234434df", name)
                )
            ).name(),
            Matchers.is(name)
        );
    }

}
