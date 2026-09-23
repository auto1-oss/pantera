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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * B77: registries that do not support deletion MUST answer 405 on the
 * manifest and blob DELETE endpoints (OCI distribution spec), not a bare
 * 404 that tells the client the image does not exist.
 */
final class DeleteUnsupportedTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/v2/my-app/manifests/1.0",
        "/v2/my-app/manifests/sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "/v2/my-app/blobs/sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    })
    void answersMethodNotAllowed(final String path) {
        MatcherAssert.assertThat(
            new DockerSlice(new AstoDocker("test_registry", new InMemoryStorage()))
                .response(new RequestLine(RqMethod.DELETE, path), Headers.EMPTY, Content.EMPTY)
                .join(),
            new IsErrorsResponse(RsStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED")
        );
    }
}
