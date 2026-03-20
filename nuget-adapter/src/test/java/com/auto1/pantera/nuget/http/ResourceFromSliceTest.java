/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Tests for {@link ResourceFromSlice}.
 */
final class ResourceFromSliceTest {

    @Test
    void shouldDelegateGetResponse() {
        final String path = "/some/path";
        final Header header = new Header("Name", "Value");
        final Response response = new ResourceFromSlice(
            path, (line, hdrs, body) -> ResponseBuilder.ok().headers(hdrs)
            .body(line.toString().getBytes()).completedFuture()
        ).get(Headers.from(Collections.singleton(header))).join();
        MatcherAssert.assertThat(
            response,
            Matchers.allOf(
                new RsHasStatus(RsStatus.OK),
                new RsHasHeaders(header),
                new RsHasBody(
                    new RequestLine(RqMethod.GET, path).toString().getBytes()
                )
            )
        );
    }

    @Test
    void shouldDelegatePutResponse() {
        final RsStatus status = RsStatus.OK;
        final String path = "/some/other/path";
        final Header header = new Header("X-Name", "Something");
        final String content = "body";
        final Response response = new ResourceFromSlice(
            path,
            (line, hdrs, body) -> ResponseBuilder.ok().headers(hdrs)
                .body(Flowable.concat(Flowable.just(ByteBuffer.wrap(line.toString().getBytes())), body))
                .completedFuture()
        ).put(
            Headers.from(Collections.singleton(header)),
            new Content.From(content.getBytes())
        ).join();
        MatcherAssert.assertThat(
            response,
            Matchers.allOf(
                new RsHasStatus(status),
                new RsHasHeaders(header),
                new RsHasBody(
                    String.join("", new RequestLine(RqMethod.PUT, path).toString(), content)
                        .getBytes()
                )
            )
        );
    }
}
