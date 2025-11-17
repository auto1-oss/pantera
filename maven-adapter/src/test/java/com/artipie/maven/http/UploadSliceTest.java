/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.asto.test.ContentIs;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UploadSlice}.
 */
class UploadSliceTest {

    /**
     * Test storage.
     */
    private Storage asto;

    /**
     * Update maven slice.
     */
    private Slice ums;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.ums = new UploadSlice(this.asto);
    }

    @Test
    void savesDataDirectly() {
        final byte[] data = "jar content".getBytes();
        MatcherAssert.assertThat(
            "Wrong response status, CREATED is expected",
            this.ums,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, "/com/artipie/asto/0.1/asto-0.1.jar"),
                Headers.from(new ContentLength(data.length)),
                new Content.From(data)
            )
        );
        MatcherAssert.assertThat(
            "Uploaded data were not saved to storage",
            this.asto.value(new Key.From("com/artipie/asto/0.1/asto-0.1.jar")).join(),
            new ContentIs(data)
        );
    }

    @Test
    void stripsMetadataPropertiesFromFilename() {
        // Test that semicolon-separated metadata properties are stripped from the filename
        // to avoid exceeding filesystem filename length limits (typically 255 bytes)
        final byte[] data = "graphql content".getBytes();
        final String pathWithMetadata =
            "/wkda/common/graphql/vehicle/1.0.0-395-202511111100/" +
            "vehicle-1.0.0-395-202511111100.graphql;" +
            "vcs.revision=6177d00b21602d4a23f004ce5bd1dc56e5154ed4;" +
            "build.timestamp=1762855225704;" +
            "build.name=libraries+::+graphql-schema-specification-build-deploy+::+master;" +
            "build.number=395;" +
            "vcs.branch=master;" +
            "vcs.url=git@github.com:wkda/graphql-schema-specification.git";

        MatcherAssert.assertThat(
            "Wrong response status, CREATED is expected",
            this.ums,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, pathWithMetadata),
                Headers.from(new ContentLength(data.length)),
                new Content.From(data)
            )
        );

        // Verify the file was saved WITHOUT the metadata properties
        final Key expectedKey = new Key.From(
            "wkda/common/graphql/vehicle/1.0.0-395-202511111100/" +
            "vehicle-1.0.0-395-202511111100.graphql"
        );
        MatcherAssert.assertThat(
            "Uploaded data should be saved without metadata properties",
            this.asto.value(expectedKey).join(),
            new ContentIs(data)
        );
    }

}
