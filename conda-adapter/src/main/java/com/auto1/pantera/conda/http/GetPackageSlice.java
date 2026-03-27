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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Package slice returns info about package, serves on `GET /package/{owner_login}/{package_name}`.
 * @since 0.4
 * @todo #32:30min Implement get package slice to provide package info if the package exists. For
 *  any details check swagger docs:
 *  https://api.anaconda.org/docs#!/package/get_package_owner_login_package_name
 *  Now this slice always returns `package not found` error.
 */
public final class GetPackageSlice implements Slice {

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers,
                                                final Content body) {
        return ResponseBuilder.notFound()
            .jsonBody(Json.createObjectBuilder().add(
                "error", String.format(
                    "\"%s\" could not be found",
                    new KeyLastPart(new KeyFromPath(line.uri().getPath())).get()
                )).build())
            .completedFuture();
    }
}
