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

import com.auto1.pantera.docker.http.manifest.ManifestRequest;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ManifestRequest}.
 */
class ManifestRequestTest {

    @Test
    void shouldReadName() {
        ManifestRequest request = ManifestRequest.from(
            new RequestLine(RqMethod.GET, "/v2/my-repo/manifests/3")
        );
        MatcherAssert.assertThat(request.name(), Matchers.is("my-repo"));
    }

    @Test
    void shouldReadReference() {
        ManifestRequest request = ManifestRequest.from(
            new RequestLine(RqMethod.GET, "/v2/my-repo/manifests/sha256:123abc")
        );
        MatcherAssert.assertThat(request.reference().digest(), Matchers.is("sha256:123abc"));
    }

    @Test
    void shouldReadCompositeName() {
        final String name = "zero-one/two.three/four_five";
        MatcherAssert.assertThat(
            ManifestRequest.from(
                new RequestLine(
                    "HEAD", String.format("/v2/%s/manifests/sha256:234434df", name)
                )
            ).name(),
            Matchers.is(name)
        );
    }

}
