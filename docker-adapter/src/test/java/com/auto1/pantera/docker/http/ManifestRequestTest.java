/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
