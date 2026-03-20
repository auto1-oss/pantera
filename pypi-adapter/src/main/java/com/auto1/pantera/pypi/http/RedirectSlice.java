/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.pypi.NormalizedProjectName;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Single;

import java.util.concurrent.CompletableFuture;

/**
 * Slice to redirect to normalized url.
 * @since 0.6
 */
public final class RedirectSlice implements Slice {

    /**
     * Full path header name.
     */
    private static final String HDR_FULL_PATH = "X-FullPath";

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String rqline = line.uri().toString();
        final String last = rqline.split("/")[rqline.split("/").length - 1];
        return Single.fromCallable(() -> last)
            .map(name -> new NormalizedProjectName.Simple(name).value())
            .map(
                normalized -> new RqHeaders(headers, RedirectSlice.HDR_FULL_PATH).stream()
                    .findFirst()
                    .orElse(rqline).replaceAll(String.format("(%s\\/?)$", last), normalized)
            )
            .map(
                url -> ResponseBuilder.movedPermanently().header("Location", url).build()
            ).to(SingleInterop.get()).toCompletableFuture();
    }
}
