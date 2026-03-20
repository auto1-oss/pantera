/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.ResponseBuilder;

import java.util.concurrent.CompletableFuture;

/**
 * Slice created from {@link Resource}.
 */
final class SliceFromResource implements Slice {

    /**
     * Origin resource.
     */
    private final Resource origin;

    /**
     * @param origin Origin resource.
     */
    SliceFromResource(final Resource origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final RqMethod method = line.method();
        if (method.equals(RqMethod.GET)) {
            return this.origin.get(headers);
        }
        if (method.equals(RqMethod.PUT)) {
            return this.origin.put(headers, body);
        }
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }
}
