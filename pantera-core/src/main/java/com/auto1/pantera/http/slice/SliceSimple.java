/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Simple decorator for Slice.
 */
public final class SliceSimple implements Slice {

    private final Supplier<Response> res;

    public SliceSimple(Response response) {
        this.res = () -> response;
    }

    public SliceSimple(Supplier<Response> res) {
        this.res = res;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return CompletableFuture.completedFuture(this.res.get());
    }
}
