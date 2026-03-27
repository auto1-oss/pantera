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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.concurrent.CompletableFuture;

/**
 * Slice limiting requests size by `Content-Length` header.
 * Checks `Content-Length` header to be within limit and responds with error if it is not.
 * Forwards request to delegate {@link Slice} otherwise.
 */
public final class ContentLengthRestriction implements Slice {

    /**
     * Delegate slice.
     */
    private final Slice delegate;

    /**
     * Max allowed value.
     */
    private final long limit;

    /**
     * @param delegate Delegate slice.
     * @param limit Max allowed value.
     */
    public ContentLengthRestriction(final Slice delegate, final long limit) {
        this.delegate = delegate;
        this.limit = limit;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        if (new RqHeaders(headers, "Content-Length").stream().allMatch(this::withinLimit)) {
            return this.delegate.response(line, headers, body);
        }
        return CompletableFuture.completedFuture(ResponseBuilder.payloadTooLarge().build());
    }

    /**
     * Checks that value is less or equal then limit.
     *
     * @param value Value to check against limit.
     * @return True if value is within limit or cannot be parsed, false otherwise.
     */
    private boolean withinLimit(final String value) {
        boolean pass;
        try {
            pass = Long.parseLong(value) <= this.limit;
        } catch (final NumberFormatException ex) {
            pass = true;
        }
        return pass;
    }
}
