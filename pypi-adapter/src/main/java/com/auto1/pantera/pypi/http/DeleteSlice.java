/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.util.concurrent.CompletableFuture;

public final class DeleteSlice implements Slice {
    private final Storage asto;

    public DeleteSlice(final Storage asto) {
        this.asto = asto;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = new KeyFromPath(line.uri().getPath());


        return this.asto.exists(key).thenCompose(
                exists -> {
                    if (exists) {
                        return this.asto.delete(key).thenApply(
                                nothing -> ResponseBuilder.ok().build()
                        ).toCompletableFuture();
                    } else {
                        // Consume request body to prevent Vert.x request leak
                        return body.asBytesFuture().thenApply(ignored ->
                            ResponseBuilder.notFound().build()
                        );
                    }
                }
        );
    }
}
