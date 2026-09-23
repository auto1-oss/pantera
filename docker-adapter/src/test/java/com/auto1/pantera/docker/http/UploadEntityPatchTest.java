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
import com.auto1.pantera.docker.Digest;
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
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * B75: chunked upload per the distribution spec, then PUT to commit.
     */
    @Test
    void shouldAcceptSecondChunkAndCommitConcatenation() {
        final String name = "test";
        final String uuid = this.docker.repo(name).uploads().start().join().uuid();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, uuid);
        this.patch(path, "0-3", "data");
        final Response second = this.patch(path, "4-8", "chunk");
        final byte[] whole = "datachunk".getBytes();
        final String digest = new Digest.Sha256(whole).string();
        final Response put = this.slice.response(
            new RequestLine(RqMethod.PUT, String.format("%s?digest=%s", path, digest)),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "second chunk is accepted",
            second.status(), new IsEqual<>(RsStatus.ACCEPTED)
        );
        MatcherAssert.assertThat(
            "range covers both chunks",
            second.headers().values("Range"), new IsEqual<>(List.of("0-8"))
        );
        MatcherAssert.assertThat(
            "commit succeeds",
            put.status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "stored blob is the concatenation",
            this.docker.repo(name).layers().get(new Digest.FromString(digest)).join()
                .orElseThrow().content().join().asBytes(),
            new IsEqual<>(whole)
        );
    }

    @Test
    void shouldAnswer416ForOutOfOrderChunk() {
        final String name = "test";
        final String uuid = this.docker.repo(name).uploads().start().join().uuid();
        final String path = String.format("/v2/%s/blobs/uploads/%s", name, uuid);
        this.patch(path, "0-3", "data");
        final Response skipped = this.patch(path, "10-14", "chunk");
        MatcherAssert.assertThat(
            "out-of-order chunk is refused with 416",
            skipped.status(), new IsEqual<>(RsStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        );
        MatcherAssert.assertThat(
            "416 reports what the registry holds",
            skipped.headers().values("Range"), new IsEqual<>(List.of("0-3"))
        );
    }

    private Response patch(final String path, final String range, final String data) {
        return this.slice.response(
            new RequestLine(RqMethod.PATCH, path),
            TestDockerAuth.headers().copy()
                .add("Content-Range", range)
                .add("Content-Length", String.valueOf(data.length())),
            new Content.From(data.getBytes())
        ).join();
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
