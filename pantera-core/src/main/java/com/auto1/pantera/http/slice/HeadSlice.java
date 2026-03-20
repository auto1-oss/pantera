/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentFileName;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A {@link Slice} which only serves metadata on Binary files.
 */
public final class HeadSlice implements Slice {

    private final Storage storage;

    /**
     * Path to key transformation.
     */
    private final Function<String, Key> transform;

    /**
     * Function to get response headers.
     */
    private final BiFunction<RequestLine, Headers, CompletionStage<Headers>> resHeaders;

    public HeadSlice(final Storage storage) {
        this(storage, KeyFromPath::new);
    }

    /**
     * @param storage Storage
     * @param transform Transformation
     */
    public HeadSlice(final Storage storage, final Function<String, Key> transform) {
        this(
            storage,
            transform,
            (line, headers) -> {
                final URI uri = line.uri();
                final Key key = transform.apply(uri.getPath());
                return storage.metadata(key)
                    .thenApply(
                        meta -> meta.read(Meta.OP_SIZE)
                            .orElseThrow(IllegalStateException::new)
                    ).thenApply(
                        size -> Headers.from(
                            new ContentFileName(uri),
                            new ContentLength(size)
                        )
                    );
            }
        );
    }

    /**
     * @param storage Storage
     * @param transform Transformation
     * @param resHeaders Function to get response headers
     */
    public HeadSlice(
        final Storage storage,
        final Function<String, Key> transform,
        final BiFunction<RequestLine, Headers, CompletionStage<Headers>> resHeaders
    ) {
        this.storage = storage;
        this.transform = transform;
        this.resHeaders = resHeaders;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = this.transform.apply(line.uri().getPath());
        return this.storage.exists(key)
            .thenCompose(
                exist -> {
                    if (exist) {
                        return this.resHeaders
                            .apply(line, headers)
                            .thenApply(res -> ResponseBuilder.ok().headers(res).build());
                    }
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound()
                            .textBody(String.format("Key %s not found", key.string()))
                            .build()
                    );
                }
            );
    }

}
