/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Slice that forwards all requests to origin slice prepending path with specified prefix.
 *
 * @since 0.3
 */
public final class PathPrefixSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Prefix.
     */
    private final String prefix;

    /**
     * Ctor.
     *
     * @param origin Origin slice.
     * @param prefix Prefix.
     */
    public PathPrefixSlice(final Slice origin, final String prefix) {
        this.origin = origin;
        this.prefix = prefix;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final URI original = line.uri();
        final String path = this.normalizePath(this.prefix, original.getRawPath());
        final String uri;
        if (original.getRawQuery() == null) {
            uri = path;
        } else {
            uri = String.format("%s?%s", path, original.getRawQuery());
        }
        return this.origin.response(
            new RequestLine(line.method().value(), uri, line.version()),
            headers,
            body
        );
    }

    /**
     * Normalize path by combining prefix and path, avoiding double slashes.
     * @param prefix Path prefix
     * @param path Request path
     * @return Normalized path
     */
    private String normalizePath(final String prefix, final String path) {
        if (prefix == null || prefix.isEmpty()) {
            return path == null ? "/" : path;
        }
        if (path == null || path.isEmpty()) {
            return prefix;
        }
        // Remove trailing slash from prefix and leading slash from path to avoid double slashes
        final String cleanPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        final String cleanPath = path.startsWith("/") ? path : "/" + path;
        return cleanPrefix + cleanPath;
    }
}
