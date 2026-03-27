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
import com.auto1.pantera.http.Response;

import java.util.concurrent.CompletableFuture;

/**
 * Resource serving HTTP requests.
 */
public interface Resource {
    /**
     * Serve GET method.
     *
     * @param headers Request headers.
     * @return Response to request.
     */
    CompletableFuture<Response> get(Headers headers);

    /**
     * Serve PUT method.
     *
     * @param headers Request headers.
     * @param body    Request body.
     * @return Response to request.
     */
    CompletableFuture<Response> put(Headers headers, Content body);
}
