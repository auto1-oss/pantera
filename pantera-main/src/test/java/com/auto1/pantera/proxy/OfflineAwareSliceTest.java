/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link OfflineAwareSlice}.
 */
class OfflineAwareSliceTest {

    @Test
    void delegatesWhenOnline() throws Exception {
        final OfflineAwareSlice slice = new OfflineAwareSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("hello").build()
            )
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/test"), Headers.EMPTY, Content.EMPTY
        ).get();
        assertThat(resp.status().code(), equalTo(200));
    }

    @Test
    void returns503WhenOffline() throws Exception {
        final OfflineAwareSlice slice = new OfflineAwareSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        );
        slice.goOffline();
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/test"), Headers.EMPTY, Content.EMPTY
        ).get();
        assertThat(resp.status().code(), equalTo(503));
    }

    @Test
    void togglesOfflineMode() {
        final OfflineAwareSlice slice = new OfflineAwareSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        );
        assertThat(slice.isOffline(), is(false));
        slice.goOffline();
        assertThat(slice.isOffline(), is(true));
        slice.goOnline();
        assertThat(slice.isOffline(), is(false));
    }
}
