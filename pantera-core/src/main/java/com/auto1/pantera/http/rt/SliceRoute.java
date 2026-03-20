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
package com.auto1.pantera.http.rt;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routing slice.
 * <p>
 * {@link Slice} implementation which redirect requests to {@link Slice} if {@link RtRule} matched.
 * <p>
 * Usage:
 * <pre><code>
 * new SliceRoute(
 *   new SliceRoute.Path(
 *     new RtRule.ByMethod("GET"), new DownloadSlice(storage)
 *   ),
 *   new SliceRoute.Path(
 *     new RtRule.ByMethod("PUT"), new UploadSlice(storage)
 *   )
 * );
 * </code></pre>
 */
public final class SliceRoute implements Slice {

    /**
     * Routes.
     */
    private final List<RtPath> routes;

    /**
     * New slice route.
     * @param routes Routes
     */
    public SliceRoute(final RtPath... routes) {
        this(Arrays.asList(routes));
    }

    /**
     * New slice route.
     * @param routes Routes
     */
    public SliceRoute(final List<RtPath> routes) {
        this.routes = routes;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        return this.routes.stream()
            .map(item -> item.response(line, headers, body))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst()
            .orElse(CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ));
    }
}
