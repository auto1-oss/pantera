/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link RedirectSlice}.
 */
class RedirectSliceTest {

    @Test
    void redirectsToNormalizedName() {
        ResponseAssert.check(
            new RedirectSlice()
                .response(new RequestLine(RqMethod.GET, "/one/two/three_four"),
                    Headers.EMPTY, Content.EMPTY)
                .join(),
            RsStatus.MOVED_PERMANENTLY,
            new Header("Location", "/one/two/three-four")
        );
    }

    @Test
    void redirectsToNormalizedNameWithSlashAtTheEnd() {
        ResponseAssert.check(
            new RedirectSlice().response(
                new RequestLine(RqMethod.GET, "/one/two/three_four/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            RsStatus.MOVED_PERMANENTLY,
            new Header("Location", "/one/two/three-four")
        );
    }

    @Test
    void redirectsToNormalizedNameWhenFillPathIsPresent() {
        ResponseAssert.check(
            new RedirectSlice().response(
                new RequestLine(RqMethod.GET, "/three/F.O.U.R"),
                Headers.from("X-FullPath", "/one/two/three/F.O.U.R"),
                Content.EMPTY
            ).join(),
            RsStatus.MOVED_PERMANENTLY,
            new Header("Location", "/one/two/three/f-o-u-r")
        );
    }

    @Test
    void normalizesOnlyLastPart() {
        ResponseAssert.check(
            new RedirectSlice().response(
                new RequestLine(RqMethod.GET, "/three/One_Two"),
                Headers.from("X-FullPath", "/One_Two/three/One_Two"),
                Content.EMPTY
            ).join(),
            RsStatus.MOVED_PERMANENTLY,
            new Header("Location", "/One_Two/three/one-two")
        );
    }

}
