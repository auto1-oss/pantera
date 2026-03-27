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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;

import java.util.concurrent.CompletableFuture;

/**
 * Absent resource, sends HTTP 404 Not Found response to every request.
 */
public final class Absent implements Resource {

    @Override
    public CompletableFuture<Response> get(final Headers headers) {
        return ResponseBuilder.notFound().completedFuture();
    }

    @Override
    public CompletableFuture<Response> put(Headers headers, Content body) {
        return ResponseBuilder.notFound().completedFuture();
    }
}
