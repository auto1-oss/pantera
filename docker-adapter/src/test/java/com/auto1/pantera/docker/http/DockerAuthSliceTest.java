/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link DockerAuthSlice}.
 */
public final class DockerAuthSliceTest {

    @Test
    void shouldReturnErrorsWhenUnathorized() {
        final Headers headers = Headers.from(
            new WwwAuthenticate("Basic"),
            new Header("X-Something", "Value")
        );
        MatcherAssert.assertThat(
            new DockerAuthSlice(
                (rqline, rqheaders, rqbody) -> ResponseBuilder.unauthorized().headers(headers).completedFuture()
            ).response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY, Content.EMPTY
            ).join(),
            new AllOf<>(
                new IsErrorsResponse(RsStatus.UNAUTHORIZED, "UNAUTHORIZED"),
                new RsHasHeaders(
                    new WwwAuthenticate("Basic"),
                    new Header("X-Something", "Value"),
                    ContentType.json(),
                    new ContentLength("72")
                )
            )
        );
    }

    @Test
    void shouldNotModifyNormalResponse() {
        final RsStatus status = RsStatus.OK;
        final byte[] body = "data".getBytes();
        ResponseAssert.check(
            new DockerAuthSlice(
                (rqline, rqheaders, rqbody) -> ResponseBuilder.ok()
                    .header(ContentType.text())
                    .body(body)
                    .completedFuture()
            ).response(
                new RequestLine(RqMethod.GET, "/some/path"),
                Headers.EMPTY, Content.EMPTY
            ).join(),
            status, body, ContentType.text()
        );
    }
}
