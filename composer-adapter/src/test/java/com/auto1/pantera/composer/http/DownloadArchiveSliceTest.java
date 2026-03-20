/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DownloadArchiveSlice}.
 * @since 0.4
 */
final class DownloadArchiveSliceTest {
    @Test
    void returnsOkStatus() {
        final Storage storage = new InMemoryStorage();
        final String archive = "log-1.1.3.zip";
        final Key key = new Key.From("artifacts", archive);
        new TestResource(archive)
            .saveTo(storage, key);
        MatcherAssert.assertThat(
            new DownloadArchiveSlice(new AstoRepository(storage)),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, key.string()),
                Headers.EMPTY,
                new Content.From(new TestResource(archive).asBytes())
            )
        );
    }
}
