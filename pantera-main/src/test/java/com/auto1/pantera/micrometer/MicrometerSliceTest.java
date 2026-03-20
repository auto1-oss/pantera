/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.micrometer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.Flowable;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Test for {@link MicrometerSlice}.
 */
class MicrometerSliceTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void init() {
        this.registry = new SimpleMeterRegistry();
    }

    @Test
    void addsSummaryToRegistry() {
        final String path = "/same/path";
        // Response body metrics now use Content-Length header instead of wrapping body stream.
        // This avoids double-subscription issues with Content.OneTime from storage.
        assertResponse(
            ResponseBuilder.ok()
                .header("Content-Length", "12")
                .body(Flowable.fromArray(
                    ByteBuffer.wrap("Hello ".getBytes(StandardCharsets.UTF_8)),
                    ByteBuffer.wrap("world!".getBytes(StandardCharsets.UTF_8))
                )).build(),
            new RequestLine(RqMethod.GET, path),
            RsStatus.OK
        );
        assertResponse(
            ResponseBuilder.ok()
                .header("Content-Length", "3")
                .body("abc".getBytes(StandardCharsets.UTF_8)).build(),
            new RequestLine(RqMethod.GET, path),
            RsStatus.OK
        );
        assertResponse(
            ResponseBuilder.from(RsStatus.CONTINUE).build(),
            new RequestLine(RqMethod.POST, "/a/b/c"),
            RsStatus.CONTINUE
        );
        String actual = registry.getMetersAsString();

        List.of(
            Matchers.containsString("artipie.request.body.size(DISTRIBUTION_SUMMARY)[method='POST']; count=0.0, total=0.0 bytes, max=0.0 bytes"),
            Matchers.containsString("artipie.request.body.size(DISTRIBUTION_SUMMARY)[method='GET']; count=0.0, total=0.0 bytes, max=0.0 bytes"),
            Matchers.containsString("artipie.request.counter(COUNTER)[method='POST', status='CONTINUE']; count=1.0"),
            Matchers.containsString("artipie.request.counter(COUNTER)[method='GET', status='OK']; count=2.0"),
            Matchers.containsString("artipie.response.body.size(DISTRIBUTION_SUMMARY)[method='POST']; count=0.0, total=0.0 bytes, max=0.0 bytes"),
            // Response body size now tracked via Content-Length header: 12 + 3 = 15 bytes total, 2 responses
            Matchers.containsString("artipie.response.body.size(DISTRIBUTION_SUMMARY)[method='GET']; count=2.0, total=15.0 bytes, max=12.0 bytes"),
            Matchers.containsString("artipie.slice.response(TIMER)[status='OK']; count=2.0, total_time"),
            Matchers.containsString("artipie.slice.response(TIMER)[status='CONTINUE']; count=1.0, total_time")
        ).forEach(m -> MatcherAssert.assertThat(actual, m));
    }

    private void assertResponse(Response res, RequestLine line, RsStatus expected) {
        Slice slice = new MicrometerSlice(new SliceSimple(res), this.registry);
        Response actual = slice.response(line, Headers.EMPTY, Content.EMPTY).join();
        ResponseAssert.check(actual, expected);
        actual.body().asString();
    }
}
