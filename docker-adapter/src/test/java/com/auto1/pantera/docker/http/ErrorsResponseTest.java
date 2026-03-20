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

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.BlobUnknownError;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.hm.RsHasBody;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@code com.auto1.pantera.docker.error.DockerError.json()}.
 */
public final class ErrorsResponseTest {
    @Test
    void shouldHaveExpectedBody() {
        MatcherAssert.assertThat(
            ResponseBuilder.notFound()
                .jsonBody(new BlobUnknownError(new Digest.Sha256("123")).json())
                .build(),
            new RsHasBody(
                "{\"errors\":[{\"code\":\"BLOB_UNKNOWN\",\"message\":\"blob unknown to registry\",\"detail\":\"sha256:123\"}]}".getBytes()
            )
        );
    }
}
