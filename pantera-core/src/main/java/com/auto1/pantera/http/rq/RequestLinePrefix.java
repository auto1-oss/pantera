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
package com.auto1.pantera.http.rq;

import com.auto1.pantera.http.Headers;

/**
 * Path prefix obtained from X-FullPath header and request line.
 */
public final class RequestLinePrefix {

    /**
     * Full path header name.
     */
    private static final String HDR_FULL_PATH = "X-FullPath";

    /**
     * Request line.
     */
    private final String line;

    /**
     * Headers.
     */
    private final Headers headers;

    /**
     * Ctor.
     * @param line Request line
     * @param headers Request headers
     */
    public RequestLinePrefix(final String line, final Headers headers) {
        this.line = line;
        this.headers = headers;
    }

    /**
     * Obtains path prefix by `X-FullPath` header and request line. If header is absent, empty line
     * is returned.
     * @return Path prefix
     */
    public String get() {
        return new RqHeaders(this.headers, RequestLinePrefix.HDR_FULL_PATH).stream()
            .findFirst()
            .map(
                item -> {
                    final String res;
                    final String first = this.line.replaceAll("^/", "").replaceAll("/$", "")
                        .split("/")[0];
                    if (item.indexOf(first) > 0) {
                        res = item.substring(0, item.indexOf(first) - 1);
                    } else {
                        res = item;
                    }
                    return res;
                }
            ).orElse("");
    }
}
