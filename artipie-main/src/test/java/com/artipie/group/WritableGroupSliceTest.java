/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link WritableGroupSlice}.
 */
class WritableGroupSliceTest {

    @Test
    void routesGetToReadDelegate() throws Exception {
        final AtomicBoolean readCalled = new AtomicBoolean(false);
        final Slice readSlice = (line, headers, body) -> {
            readCalled.set(true);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Slice writeSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final Response resp = new WritableGroupSlice(readSlice, writeSlice)
            .response(new RequestLine(RqMethod.GET, "/test"), Headers.EMPTY, Content.EMPTY)
            .get();
        assertThat(readCalled.get(), is(true));
        assertThat(resp.status().code(), equalTo(200));
    }

    @Test
    void routesPutToWriteTarget() throws Exception {
        final AtomicBoolean writeCalled = new AtomicBoolean(false);
        final Slice readSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final Slice writeSlice = (line, headers, body) -> {
            writeCalled.set(true);
            return CompletableFuture.completedFuture(ResponseBuilder.created().build());
        };
        final Response resp = new WritableGroupSlice(readSlice, writeSlice)
            .response(new RequestLine(RqMethod.PUT, "/test"), Headers.EMPTY, Content.EMPTY)
            .get();
        assertThat(writeCalled.get(), is(true));
        assertThat(resp.status().code(), equalTo(201));
    }

    @Test
    void routesDeleteToWriteTarget() throws Exception {
        final AtomicBoolean writeCalled = new AtomicBoolean(false);
        final Slice writeSlice = (line, headers, body) -> {
            writeCalled.set(true);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Slice readSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        new WritableGroupSlice(readSlice, writeSlice)
            .response(new RequestLine(RqMethod.DELETE, "/test"), Headers.EMPTY, Content.EMPTY)
            .get();
        assertThat(writeCalled.get(), is(true));
    }

    @Test
    void routesHeadToReadDelegate() throws Exception {
        final AtomicBoolean readCalled = new AtomicBoolean(false);
        final Slice readSlice = (line, headers, body) -> {
            readCalled.set(true);
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Slice writeSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        new WritableGroupSlice(readSlice, writeSlice)
            .response(new RequestLine(RqMethod.HEAD, "/test"), Headers.EMPTY, Content.EMPTY)
            .get();
        assertThat(readCalled.get(), is(true));
    }
}
