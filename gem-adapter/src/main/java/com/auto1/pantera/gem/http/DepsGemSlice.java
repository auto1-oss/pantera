/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.gem.Gem;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;
import io.reactivex.Flowable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

/**
 * Dependency API slice implementation.
 */
final class DepsGemSlice implements Slice {

    /**
     * Repository storage.
     */
    private final Storage repo;

    /**
     * New dependency slice.
     * @param repo Repository storage
     */
    DepsGemSlice(final Storage repo) {
        this.repo = repo;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers,
                                                final Content body) {
        return new Gem(this.repo).dependencies(
            Collections.unmodifiableSet(
                new HashSet<>(
                    new RqParams(line.uri()).value("gems")
                        .map(str -> Arrays.asList(str.split(",")))
                        .orElse(Collections.emptyList())
                )
            )
        ).thenApply(
            data -> ResponseBuilder.ok()
                .body(Flowable.just(data))
                .build()
        ).toCompletableFuture();
    }
}
