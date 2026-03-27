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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.ExampleStorage;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DockerSlice}.
 * Manifest GET endpoint.
 */
class ManifestEntityGetTest {

    /**
     * Slice being tested.
     */
    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.slice = new DockerSlice(new AstoDocker("test_registry", new ExampleStorage()));
    }

    @Test
    void shouldReturnManifestByTag() {
        MatcherAssert.assertThat(
            this.slice.response(
                new RequestLine(RqMethod.GET, "/v2/my-alpine/manifests/1"),
                Headers.from(
                    new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json, application/xml;q=0.9, image/webp")
                ),
                Content.EMPTY
            ).join(),
            new ResponseMatcher(
                "sha256:cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221",
                bytes(
                    new Key.From(
                        "blobs", "sha256", "cb",
                        "cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221", "data"
                    )
                )
            )
        );
    }

    @Test
    void shouldReturnManifestByDigest() {
        final String hex = "cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221";
        final String digest = String.format("%s:%s", "sha256", hex);
        MatcherAssert.assertThat(
            this.slice.response(
                new RequestLine(
                    RqMethod.GET,
                    String.format("/v2/my-alpine/manifests/%s", digest)
                ),
                Headers.from(
                    new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json, application/xml;q=0.9, image/webp")
                ),
                Content.EMPTY
            ).join(),
            new ResponseMatcher(
                digest,
                bytes(new Key.From("blobs", "sha256", "cb", hex, "data"))
            )
        );
    }

    @Test
    void shouldReturnNotFoundForUnknownTag() {
        MatcherAssert.assertThat(
            this.slice.response(
                new RequestLine(RqMethod.GET, "/v2/my-alpine/manifests/2"),
                Headers.from(
                    new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json, application/xml;q=0.9, image/webp")
                ),
                Content.EMPTY
            ).join(),
            new IsErrorsResponse(RsStatus.NOT_FOUND, "MANIFEST_UNKNOWN")
        );
    }

    @Test
    void shouldReturnNotFoundForUnknownDigest() {
        MatcherAssert.assertThat(
            this.slice.response(
                new RequestLine(
                    RqMethod.GET,
                    String.format(
                        "/v2/my-alpine/manifests/%s",
                        "sha256:0123456789012345678901234567890123456789012345678901234567890123"
                    )
                ),
                Headers.from(
                    new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json, application/xml;q=0.9, image/webp")
                ),
                Content.EMPTY
            ).join(),
            new IsErrorsResponse(RsStatus.NOT_FOUND, "MANIFEST_UNKNOWN")
        );
    }

    private static byte[] bytes(final Key key) {
        return new ExampleStorage().value(key).join().asBytes();
    }


    /**
     * Response matcher.
     */
    private static final class ResponseMatcher extends AllOf<Response> {

        /**
         * @param digest Digest
         * @param content Content
         */
        ResponseMatcher(final String digest, final byte[] content) {
            super(
                new RsHasStatus(RsStatus.OK),
                new RsHasHeaders(
                    new Header("Content-Length", String.valueOf(content.length)),
                    new Header(
                        "Content-Type",
                        "application/vnd.docker.distribution.manifest.v2+json"
                    ),
                    new Header("Docker-Content-Digest", digest)
                ),
                new RsHasBody(content)
            );
        }
    }
}
