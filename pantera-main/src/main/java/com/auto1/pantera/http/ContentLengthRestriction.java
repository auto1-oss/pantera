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
import com.auto1.pantera.http.body.BoundedContent;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.concurrent.CompletableFuture;

/**
 * Slice limiting request body size.
 *
 * <p>A declared {@code Content-Length} above the limit (or one that does not
 * parse) is refused with 413 before the delegate runs. The body handed to
 * the delegate is additionally metered on ACTUAL bytes via
 * {@link BoundedContent}, so a chunked request (no {@code Content-Length})
 * is bounded exactly like a declared one. Before 2.2.9 only the header was
 * inspected — an absent, chunked or malformed header passed — so the
 * operator's {@code content-length-max} could be bypassed by simply not
 * declaring a length (resource-dos F17).</p>
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
        if (!new RqHeaders(headers, "Content-Length").stream().allMatch(this::withinLimit)) {
            return CompletableFuture.completedFuture(ResponseBuilder.payloadTooLarge().build());
        }
        // Meter the real bytes regardless of framing; a metered overflow
        // surfaces from the delegate as RequestBodyTooLargeException.
        return this.delegate.response(line, headers, new BoundedContent(body, this.limit))
            .handle((response, error) -> {
                if (error == null) {
                    return response;
                }
                if (RequestBodyTooLargeException.isCause(error)) {
                    return ResponseBuilder.payloadTooLarge().build();
                }
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new java.util.concurrent.CompletionException(error);
            });
    }

    /**
     * Checks that a declared value is within the limit. A value that does
     * not parse is NOT within the limit — treating it as such let a client
     * dodge the cap with a non-numeric header.
     *
     * @param value Value to check against limit.
     * @return True only if the value parses and is less or equal to the limit.
     */
    private boolean withinLimit(final String value) {
        boolean pass;
        try {
            pass = Long.parseLong(value) <= this.limit;
        } catch (final NumberFormatException ex) {
            pass = false;
        }
        return pass;
    }
}
