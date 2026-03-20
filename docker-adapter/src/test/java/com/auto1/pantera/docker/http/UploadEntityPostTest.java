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
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.TrustedBlobSource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseMatcher;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.google.common.base.Strings;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DockerSlice}.
 * Upload PUT endpoint.
 */
class UploadEntityPostTest {

    /**
     * Docker instance used in tests.
     */
    private Docker docker;

    /**
     * Slice being tested.
     */
    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = TestDockerAuth.slice(this.docker);
    }

    @Test
    void shouldStartUpload() {
        uploadStartedAssert(
            this.slice.response(
                new RequestLine(RqMethod.POST, "/v2/test/blobs/uploads/"),
                TestDockerAuth.headers(),
                Content.EMPTY
            ).join()
        );
    }

    @Test
    void shouldStartUploadIfMountNotExists() {
        uploadStartedAssert(
            TestDockerAuth.slice(this.docker).response(
                new RequestLine(
                    RqMethod.POST,
                    "/v2/test/blobs/uploads/?mount=sha256:123&from=test"
                ), TestDockerAuth.headers(), Content.EMPTY
            ).join()
        );
    }

    @Test
    void shouldMountBlob() {
        final String digest = String.format(
            "%s:%s",
            "sha256",
            "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7"
        );
        final String from = "my-alpine";
        this.docker.repo(from).layers().put(
            new TrustedBlobSource("data".getBytes())
        ).toCompletableFuture().join();
        final String name = "test";
        MatcherAssert.assertThat(
            this.slice,
            new SliceHasResponse(
                new ResponseMatcher(
                    RsStatus.CREATED,
                    new Header("Location", String.format("/v2/%s/blobs/%s", name, digest)),
                    new Header("Content-Length", "0"),
                    new Header("Docker-Content-Digest", digest)
                ),
                new RequestLine(
                    RqMethod.POST,
                    String.format("/v2/%s/blobs/uploads/?mount=%s&from=%s", name, digest, from)
                ),
                TestDockerAuth.headers(),
                Content.EMPTY
            )
        );
    }

    private static void uploadStartedAssert(Response actual) {
        Assertions.assertEquals("0-0", actual.headers().single("Range").getValue());
        Assertions.assertEquals("0", actual.headers().single("Content-Length").getValue());
        Assertions.assertTrue(
            actual.headers().single("Location").getValue()
                .startsWith("/v2/test/blobs/uploads/")
        );
        Assertions.assertFalse(
            Strings.isNullOrEmpty(actual.headers().single("Docker-Upload-UUID").getValue())
        );
    }
}
