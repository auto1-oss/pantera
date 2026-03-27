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
