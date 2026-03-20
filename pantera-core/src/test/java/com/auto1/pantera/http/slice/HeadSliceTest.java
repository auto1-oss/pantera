/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HeadSlice}.
 */
final class HeadSliceTest {

    private final Storage storage = new InMemoryStorage();

    @Test
    void returnsFound() {
        final Key key = new Key.From("foo");
        final Key another = new Key.From("bar");
        new BlockingStorage(this.storage).save(key, "anything".getBytes());
        new BlockingStorage(this.storage).save(another, "another".getBytes());
        ResponseAssert.check(
            new HeadSlice(this.storage).response(
                new RequestLine(RqMethod.HEAD, "/foo"), Headers.EMPTY, Content.EMPTY
            ).join(),
            RsStatus.OK,
            StringUtils.EMPTY.getBytes(),
            new ContentLength(8),
            new ContentDisposition("attachment; filename=\"foo\"")
        );
    }

    @Test
    void returnsNotFound() {
        ResponseAssert.check(
            new SliceDelete(this.storage).response(
                new RequestLine(RqMethod.DELETE, "/bar"), Headers.EMPTY, Content.EMPTY
            ).join(),
            RsStatus.NOT_FOUND
        );
    }
}

