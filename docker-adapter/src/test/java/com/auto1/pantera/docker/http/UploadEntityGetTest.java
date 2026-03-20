/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
 * Upload GET endpoint.
 */
public final class UploadEntityGetTest {

    private Docker docker;

    private DockerSlice slice;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = TestDockerAuth.slice(this.docker);
    }

    @Test
    void shouldReturnZeroOffsetAfterUploadStarted() {
        final String name = "test";
        final Upload upload = this.docker.repo(name)
            .uploads()
            .start()
            .toCompletableFuture().join();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, upload.uuid());
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, path),
            TestDockerAuth.headers(), Content.EMPTY
        ).join();
        ResponseAssert.check(
            response,
            RsStatus.NO_CONTENT,
            new Header("Range", "0-0"),
            new Header("Content-Length", "0"),
            new Header("Docker-Upload-UUID", upload.uuid())
        );
    }

    @Test
    void shouldReturnZeroOffsetAfterOneByteUploaded() {
        final String name = "test";
        final Upload upload = this.docker.repo(name)
            .uploads()
            .start()
            .toCompletableFuture().join();
        upload.append(new Content.From(new byte[1])).toCompletableFuture().join();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, upload.uuid());
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, path), TestDockerAuth.headers(), Content.EMPTY
        ).join();
        ResponseAssert.check(
            response,
            RsStatus.NO_CONTENT,
            new Header("Range", "0-0"),
            new Header("Content-Length", "0"),
            new Header("Docker-Upload-UUID", upload.uuid())
        );
    }

    @Test
    void shouldReturnOffsetDuringUpload() {
        final String name = "test";
        final Upload upload = this.docker.repo(name)
            .uploads()
            .start()
            .toCompletableFuture().join();
        upload.append(new Content.From(new byte[128])).toCompletableFuture().join();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, upload.uuid());
        final Response get = this.slice.response(
            new RequestLine(RqMethod.GET, path), TestDockerAuth.headers(), Content.EMPTY
        ).join();
        ResponseAssert.check(
            get,
            RsStatus.NO_CONTENT,
            new Header("Range", "0-127"),
            new Header("Content-Length", "0"),
            new Header("Docker-Upload-UUID", upload.uuid())
        );
    }

    @Test
    void shouldReturnNotFoundWhenUploadNotExists() {
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/v2/test/blobs/uploads/12345"),
            TestDockerAuth.headers(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response,
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UPLOAD_UNKNOWN")
        );
    }
}
