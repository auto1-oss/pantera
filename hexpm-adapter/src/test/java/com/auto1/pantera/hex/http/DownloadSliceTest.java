/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.hex.ResourceUtil;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;

/**
 * Test for {@link DownloadSlice}.
 */
class DownloadSliceTest {

    private Storage storage;

    private Slice slice;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.slice = new DownloadSlice(this.storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/packages/not_artifact", "/tarballs/not_artifact-0.1.0.tar"})
    void notFound(final String path) {
        MatcherAssert.assertThat(
            this.slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, path)
            )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"packages/decimal", "tarballs/decimal-2.0.0.tar"})
    void downloadOk(final String path) throws Exception {
        final byte[] bytes = Files.readAllBytes(new ResourceUtil(path).asPath());
        this.storage.save(new Key.From(path), new Content.From(bytes));
        MatcherAssert.assertThat(
            this.slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, String.format("/%s", path)),
                Headers.from(
                    ContentType.mime("application/octet-stream"),
                    new ContentLength(bytes.length)
                ),
                new Content.From(bytes)
            )
        );
    }
}
