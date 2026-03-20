/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests ReplacePathSlice.
 */
@ExtendWith(MockitoExtension.class)
public class ReplacePathSliceTest {

    /**
     * Underlying slice mock.
     */
    @Mock
    private Slice underlying;

    @Test
    public void rootPathWorks() {
        final ArgumentCaptor<RequestLine> path = ArgumentCaptor.forClass(RequestLine.class);
        Mockito.when(
            this.underlying.response(path.capture(), Mockito.any(), Mockito.any())
        ).thenReturn(null);
        final ReplacePathSlice slice = new ReplacePathSlice("/", this.underlying);
        final RequestLine expected = RequestLine.from("GET /some-path HTTP/1.1");
        slice.response(expected, Headers.EMPTY, Content.EMPTY);
        Assertions.assertEquals(expected, path.getValue());
    }

    @Test
    public void compoundPathWorks() {
        final ArgumentCaptor<RequestLine> path = ArgumentCaptor.forClass(RequestLine.class);
        Mockito.when(
            this.underlying.response(path.capture(), Mockito.any(), Mockito.any())
        ).thenReturn(null);
        final ReplacePathSlice slice = new ReplacePathSlice(
            "/compound/ctx/path",
            this.underlying
        );
        slice.response(
            RequestLine.from("GET /compound/ctx/path/abc-def HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        );
        Assertions.assertEquals(new RequestLine("GET", "/abc-def"), path.getValue());
    }
}
