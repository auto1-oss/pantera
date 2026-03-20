/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds conditional request headers (ETag/If-None-Match, Last-Modified/If-Modified-Since)
 * for upstream requests when cached content is available.
 *
 * @since 1.20.13
 */
public final class ConditionalRequest {

    /**
     * Private ctor — static utility.
     */
    private ConditionalRequest() {
    }

    /**
     * Build conditional headers from cached metadata.
     *
     * @param cachedEtag ETag from previously cached response (if available)
     * @param cachedLastModified Last-Modified header value from cached response (if available)
     * @return Headers with conditional request fields, or empty headers if no metadata
     */
    public static Headers conditionalHeaders(
        final Optional<String> cachedEtag,
        final Optional<String> cachedLastModified
    ) {
        final List<Header> headers = new ArrayList<>(2);
        cachedEtag.ifPresent(
            etag -> headers.add(new Header("If-None-Match", etag))
        );
        cachedLastModified.ifPresent(
            lm -> headers.add(new Header("If-Modified-Since", lm))
        );
        if (headers.isEmpty()) {
            return Headers.EMPTY;
        }
        return new Headers(headers);
    }

    /**
     * Extract ETag value from response headers.
     *
     * @param headers Response headers
     * @return ETag value if present
     */
    public static Optional<String> extractEtag(final Headers headers) {
        return headers.stream()
            .filter(h -> "ETag".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(com.auto1.pantera.http.headers.Header::getValue);
    }

    /**
     * Extract Last-Modified value from response headers.
     *
     * @param headers Response headers
     * @return Last-Modified value if present
     */
    public static Optional<String> extractLastModified(final Headers headers) {
        return headers.stream()
            .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(com.auto1.pantera.http.headers.Header::getValue);
    }
}
