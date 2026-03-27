/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional slice that uses some source to create new slice
 * if this source matches specified predicate.
 * @param <T> Type of target to test
 */
public final class SliceOptional<T> implements Slice {

    /**
     * Source to create a slice.
     */
    private final Supplier<? extends T> source;

    /**
     * Predicate.
     */
    private final Predicate<? super T> predicate;

    /**
     * Origin slice.
     */
    private final Function<? super T, ? extends Slice> slice;

    /**
     * New optional slice with constant source.
     * @param source Source to check
     * @param predicate Predicate checking the source
     * @param slice Slice from source
     */
    public SliceOptional(final T source,
        final Predicate<? super T> predicate,
        final Function<? super T, ? extends Slice> slice) {
        this(() -> source, predicate, slice);
    }

    /**
     * New optional slice.
     * @param source Source to check
     * @param predicate Predicate checking the source
     * @param slice Slice from source
     */
    public SliceOptional(final Supplier<? extends T> source,
        final Predicate<? super T> predicate,
        final Function<? super T, ? extends Slice> slice) {
        this.source = source;
        this.predicate = predicate;
        this.slice = slice;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers head, Content body) {
        final T target = this.source.get();
        if (this.predicate.test(target)) {
            return this.slice.apply(target).response(line, head, body);
        }
        // Consume request body to prevent Vert.x request leak
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.notFound().build()
        );
    }
}
