/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.Upload;
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
 * Tests for {@link DockerSlice}.
 * Upload PATCH endpoint.
 */
class UploadEntityPatchTest {

    private Docker docker;

    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = TestDockerAuth.slice(this.docker);
    }

    @Test
    void shouldReturnUpdatedUploadStatus() {
        final String name = "test";
        final Upload upload = this.docker.repo(name).uploads()
            .start()
            .toCompletableFuture().join();
        final String uuid = upload.uuid();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, uuid);
        final byte[] data = "data".getBytes();
        final Response response = this.slice.response(
            new RequestLine(RqMethod.PATCH, String.format("%s", path)),
            TestDockerAuth.headers(),
            new Content.From(data)
        ).join();
        ResponseAssert.check(
            response,
            RsStatus.ACCEPTED,
            new Header("Location", path),
            new Header("Range", String.format("0-%d", data.length - 1)),
            new Header("Content-Length", "0"),
            new Header("Docker-Upload-UUID", uuid)
        );
    }

    @Test
    void shouldReturnNotFoundWhenUploadNotExists() {
        final Response response = this.slice.response(
            new RequestLine(RqMethod.PATCH, "/v2/test/blobs/uploads/12345"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response,
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UPLOAD_UNKNOWN")
        );
    }
}
