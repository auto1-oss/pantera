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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.apache.commons.lang3.RandomStringUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

/**
 * Test for {@link WheelSlice}.
 * @since 0.5
 */
class WheelSliceTest {

    /**
     * Test storage.
     */
    private Storage asto;

    /**
     * Events queue.
     */
    private Queue<ArtifactEvent> queue;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.queue = new LinkedList<>();
    }

    @Test
    void savesContentAndReturnsOk() throws IOException {
        final String boundary = "simple boundary";
        final String filename = "pantera-sample-0.2.tar";
        final byte[] body = new TestResource("pypi_repo/pantera-sample-0.2.tar").asBytes();
        MatcherAssert.assertThat(
            "Returns CREATED status",
            new WheelSlice(this.asto, Optional.of(this.queue), "test"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.POST, "/"),
                Headers.from(
                    ContentType.mime(String.format("multipart/form-data; boundary=\"%s\"", boundary))
                ),
                new Content.From(this.multipartBody(body, boundary, filename))
            )
        );
        MatcherAssert.assertThat(
            "Saves content to storage",
                this.asto.value(new Key.From("pantera-sample", "0.2", filename)).join().asBytes(),
            new IsEqual<>(body)
        );
        MatcherAssert.assertThat(
            "Added event to queue", this.queue.size() == 1
        );
        MatcherAssert.assertThat(
            "Artifact event stored per package",
            this.queue.peek().artifactName(),
            new IsEqual<>("pantera-sample")
        );
        MatcherAssert.assertThat(
            "Creates package index in .pypi folder",
            this.asto.exists(new Key.From(".pypi", "pantera-sample", "pantera-sample.html")).join()
        );
        MatcherAssert.assertThat(
            "Creates repo index in .pypi folder",
            this.asto.exists(new Key.From(".pypi", "simple.html")).join()
        );
    }

    @Test
    void savesContentByNormalizedNameAndReturnsOk() throws IOException {
        final String boundary = "my boundary";
        final String filename = "ABtests-0.0.2.1-py2.py3-none-any.whl";
        final String path = "super";
        final byte[] body = new TestResource("pypi_repo/ABtests-0.0.2.1-py2.py3-none-any.whl")
            .asBytes();
        MatcherAssert.assertThat(
            "Returns CREATED status",
            new WheelSlice(this.asto, Optional.of(this.queue), "TEST"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine("POST", String.format("/%s", path)),
                Headers.from(
                    ContentType.mime(String.format("multipart/form-data; boundary=\"%s\"", boundary))
                ),
                new Content.From(this.multipartBody(body, boundary, filename))
            )
        );
        MatcherAssert.assertThat(
            "Saves content to storage",
            this.asto.value(new Key.From(path, "abtests", "0.0.2.1", filename)).join().asBytes(),
            new IsEqual<>(body)
        );
        MatcherAssert.assertThat(
            "Added event to queue", this.queue.size() == 1
        );
        MatcherAssert.assertThat(
            "Artifact event normalized per package",
            this.queue.peek().artifactName(),
            new IsEqual<>("abtests")
        );
    }

    @Test
    void returnsBadRequestIfFileNameIsInvalid() throws IOException {
        final String boundary = RandomStringUtils.random(10);
        final String filename = "pantera-sample-2020.tar.bz2";
        final byte[] body = new TestResource("pypi_repo/pantera-sample-2.1.tar.bz2").asBytes();
        MatcherAssert.assertThat(
            "Returns BAD_REQUEST status",
            new WheelSlice(this.asto, Optional.of(this.queue), "test"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.POST, "/"),
                Headers.from(
                    ContentType.mime(String.format("multipart/form-data; boundary=\"%s\"", boundary))
                ),
                new Content.From(this.multipartBody(body, boundary, filename))
                )
        );
        MatcherAssert.assertThat(
            "Storage is empty",
            this.asto.list(Key.ROOT).join(),
            new IsEmptyCollection<>()
        );
        MatcherAssert.assertThat(
            "Event to queue is empty", this.queue.isEmpty()
        );
    }

    @Test
    void returnsBadRequestIfFileInvalid() throws IOException {
        final Storage storage = new InMemoryStorage();
        final String boundary = RandomStringUtils.random(10);
        final String filename = "myproject.whl";
        final byte[] body = "some code".getBytes();
        MatcherAssert.assertThat(
            new WheelSlice(storage, Optional.of(this.queue), "test"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.POST, "/"),
                Headers.from(
                    ContentType.mime(String.format("multipart/form-data; boundary=\"%s\"", boundary))
                ),
                new Content.From(this.multipartBody(body, boundary, filename))
            )
        );
        MatcherAssert.assertThat(
            "Event to queue is empty", this.queue.isEmpty()
        );
    }

    private byte[] multipartBody(final byte[] input, final String boundary, final String filename)
        throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(
            String.join(
                "\r\n",
                "Ignored preamble",
                String.format("--%s", boundary),
                "Content-Disposition: form-data; name=\"data\"",
                "",
                "",
                "some data",
                String.format("--%s", boundary),
                String.format(
                    "Content-Disposition: form-data; name=\"content\"; filename=\"%s\"",
                    filename
                ),
                "",
                ""
            ).getBytes(StandardCharsets.US_ASCII)
        );
        body.write(input);
        body.write(String.format("\r\n--%s--", boundary).getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

}
