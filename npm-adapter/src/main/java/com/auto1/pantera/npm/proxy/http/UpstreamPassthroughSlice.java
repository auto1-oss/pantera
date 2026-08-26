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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal read-through forward of an npm endpoint to the upstream registry —
 * no caching, no transformation. Used for {@code /-/v1/search} and dist-tag
 * GETs (WS4-npm.8), which the router previously fell through to the
 * packument route (treating the whole path as a bogus package name and
 * 404ing) because no dedicated route existed. Neither endpoint's response
 * body carries a tarball URL to rewrite (search results link to the
 * upstream package page, not a tarball; dist-tags is a bare tag-&gt;version
 * map) — unlike the packument route, nothing here needs URL rewriting.
 *
 * @since 2.3.0
 */
final class UpstreamPassthroughSlice implements Slice {

    /**
     * Upstream slice (e.g., UriClientSlice to remote registry).
     */
    private final Slice remote;

    /**
     * Short label for logging (e.g. {@code "search"}, {@code "dist-tags"}).
     */
    private final String label;

    UpstreamPassthroughSlice(final Slice remote, final String label) {
        this.remote = remote;
        this.label = label;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        EcsLogger.info("com.auto1.pantera.npm")
            .message("NPM proxy passthrough (" + this.label + ")")
            .eventCategory("web")
            .eventAction("proxy_passthrough")
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        // Materialize the body first — it may have already been partially
        // consumed by upstream logging/routing, and Content.From(byte[])
        // creates a one-shot Flowable that can only be read once.
        return body.asBytesFuture().thenCompose(bodyBytes -> {
            final java.util.List<Header> cleanList = new java.util.ArrayList<>();
            for (final Header header : headers) {
                final String name = header.getKey().toLowerCase(Locale.ROOT);
                if ("host".equals(name)
                    || "authorization".equals(name)
                    || "pantera_login".equals(name)
                    || name.startsWith("x-real")
                    || name.startsWith("x-forwarded")
                    || name.startsWith("x-fullpath")
                    || name.startsWith("x-original")
                    || "connection".equals(name)
                    || "transfer-encoding".equals(name)
                    || "content-length".equals(name)) {
                    continue;
                }
                cleanList.add(header);
            }
            final Headers clean = new Headers(cleanList);
            if (clean.values("Accept").isEmpty() && clean.values("accept").isEmpty()) {
                clean.add("Accept", "application/json");
            }
            clean.add("Content-Length", String.valueOf(bodyBytes.length));
            return this.remote.response(line, clean, new Content.From(bodyBytes));
        });
    }
}
