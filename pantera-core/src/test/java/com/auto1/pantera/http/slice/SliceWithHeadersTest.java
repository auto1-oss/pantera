/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SliceWithHeaders}.
 */
class SliceWithHeadersTest {

    @Test
    void addsHeaders() {
        final String header = "Content-type";
        final String value = "text/plain";
        MatcherAssert.assertThat(
            new SliceWithHeaders(
                new SliceSimple(ResponseBuilder.ok().build()), Headers.from(header, value)
            ).response(RequestLine.from("GET /some/text HTTP/1.1"), Headers.EMPTY, Content.EMPTY).join(),
            new RsHasHeaders(new Header(header, value))
        );
    }

    @Test
    void addsHeaderToAlreadyExistingHeaders() {
        final String hone = "Keep-alive";
        final String vone = "true";
        final String htwo = "Authorization";
        final String vtwo = "123";
        MatcherAssert.assertThat(
            new SliceWithHeaders(
                new SliceSimple(
                    ResponseBuilder.ok().header(hone, vone).build()
                ), Headers.from(htwo, vtwo)
            ).response(RequestLine.from("GET /any/text HTTP/1.1"), Headers.EMPTY, Content.EMPTY).join(),
            new RsHasHeaders(
                new Header(hone, vone), new Header(htwo, vtwo)
            )
        );
    }

}
