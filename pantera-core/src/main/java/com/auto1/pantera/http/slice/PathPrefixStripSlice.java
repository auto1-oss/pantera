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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Slice that strips specified leading path segments (aliases) from the request path
 * before delegating to the origin slice.
 *
 * <p>Useful when introducing additional compatibility prefixes such as {@code /simple}
 * for PyPI or {@code /direct-dists} for Composer while keeping the storage layout unchanged.</p>
 */
public final class PathPrefixStripSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Path prefixes (without leading slash) that should be removed when present.
     */
    private final List<String> aliases;

    /**
     * New slice.
     *
     * @param origin Origin slice
     * @param aliases Path prefixes to strip (without leading slash)
     */
    public PathPrefixStripSlice(final Slice origin, final String... aliases) {
        this.origin = origin;
        this.aliases = Arrays.asList(aliases);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final URI uri = line.uri();
        final String stripped = this.strip(uri.getRawPath());
        if (stripped.equals(uri.getRawPath())) {
            return this.origin.response(line, headers, body);
        }
        final StringBuilder rebuilt = new StringBuilder(stripped);
        if (uri.getRawQuery() != null) {
            rebuilt.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            rebuilt.append('#').append(uri.getRawFragment());
        }
        final RequestLine updated = new RequestLine(
            line.method(),
            URI.create(rebuilt.toString()),
            line.version()
        );
        return this.origin.response(updated, headers, body);
    }

    /**
     * Remove known prefixes from the provided path.
     *
     * @param path Original request path
     * @return Path without the first matching alias prefix
     */
    private String strip(final String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        for (final String alias : this.aliases) {
            final String exact = "/" + alias;
            if (path.equals(exact)) {
                return "/";
            }
            final String withTrail = exact + "/";
            if (path.startsWith(withTrail)) {
                final String remainder = path.substring(withTrail.length());
                return remainder.isEmpty() ? "/" : '/' + remainder;
            }
        }
        return path;
    }
}
