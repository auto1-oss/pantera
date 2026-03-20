/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseMatcher;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Test case for {@link SliceDownload}.
 *
 * @since 1.0
 */
public final class SliceDownloadTest {

    @Test
    void downloadsByKeyFromPath() throws Exception {
        final Storage storage = new InMemoryStorage();
        final String path = "one/two/target.txt";
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        storage.save(new Key.From(path), new Content.From(data)).get();
        MatcherAssert.assertThat(
            new SliceDownload(storage).response(
                rqLineFrom("/one/two/target.txt"), Headers.EMPTY, Content.EMPTY
            ).join(),
            new RsHasBody(data)
        );
    }

    @Test
    void returnsNotFoundIfKeyDoesntExist() {
        MatcherAssert.assertThat(
            new SliceDownload(new InMemoryStorage()).response(
                rqLineFrom("/not-exists"), Headers.EMPTY, Content.EMPTY
            ).join(),
            new RsHasStatus(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void returnsOkOnEmptyValue() throws Exception {
        final Storage storage = new InMemoryStorage();
        final String path = "empty.txt";
        final byte[] body = new byte[0];
        storage.save(new Key.From(path), new Content.From(body)).get();
        MatcherAssert.assertThat(
            new SliceDownload(storage).response(
                rqLineFrom("/empty.txt"), Headers.EMPTY, Content.EMPTY
            ).join(),
            new ResponseMatcher(body)
        );
    }

    @Test
    void downloadsByKeyFromPathAndHasProperHeader() throws Exception {
        final Storage storage = new InMemoryStorage();
        final String path = "some/path/target.txt";
        final byte[] data = "goodbye".getBytes(StandardCharsets.UTF_8);
        storage.save(new Key.From(path), new Content.From(data)).get();
        MatcherAssert.assertThat(
            new SliceDownload(storage).response(
                rqLineFrom(path), Headers.EMPTY, Content.EMPTY
            ).join(),
            new RsHasHeaders(
                new Header("Content-Length", "7"),
                new Header("Content-Disposition", "attachment; filename=\"target.txt\"")
            )
        );
    }

    private static RequestLine rqLineFrom(final String path) {
        return new RequestLine("GET", path, "HTTP/1.1");
    }
}
