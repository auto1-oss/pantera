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
import com.auto1.pantera.docker.asto.Upload;
import com.auto1.pantera.docker.http.upload.DeleteUploadSlice;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeleteUploadSlice}.
 * Upload DElETE endpoint.
 */
final class DeleteUploadSliceTest {
    /**
     * Docker registry used in tests.
     */
    private Docker docker;

    /**
     * Slice being tested.
     */
    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("registry", new InMemoryStorage());
        this.slice = TestDockerAuth.slice(this.docker);
    }

    @Test
    void shouldCancelUpload() {
        final String name = "test";
        final Upload upload = this.docker.repo(name)
            .uploads()
            .start()
            .toCompletableFuture().join();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, upload.uuid());
        final Response get = this.slice.response(
            new RequestLine(RqMethod.DELETE, String.format("%s", path)),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        ResponseAssert.check(get,
            RsStatus.OK, new Header("Docker-Upload-UUID", upload.uuid()));
    }

    @Test
    void shouldNotCancelUploadTwice() {
        final String name = "test";
        final Upload upload = this.docker.repo(name)
            .uploads()
            .start()
            .toCompletableFuture().join();
        upload.cancel().toCompletableFuture().join();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, upload.uuid());
        final Response get = this.slice.response(
            new RequestLine(RqMethod.DELETE, String.format("%s", path)),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(get,
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UPLOAD_UNKNOWN")
        );
    }

}
