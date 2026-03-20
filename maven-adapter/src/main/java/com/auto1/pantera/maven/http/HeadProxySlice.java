/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Head slice for Maven proxy.
 * @since 0.5
 */
final class HeadProxySlice implements Slice {

    /**
     * Client slice.
     */
    private final Slice client;

    /**
     * New slice for {@code HEAD} requests.
     * @param client HTTP client slice
     */
    HeadProxySlice(final Slice client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> 
                // CRITICAL: Must consume body even for HEAD requests to prevent Vert.x request leak
                // This is the same pattern as Docker ProxyLayers fix
                resp.body().asBytesFuture().thenApply(ignored ->
                    ResponseBuilder.from(resp.status()).headers(resp.headers()).build()
                )
            );
    }
}
