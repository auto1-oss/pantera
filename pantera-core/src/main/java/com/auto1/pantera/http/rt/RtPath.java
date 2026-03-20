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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;

/**
 * Route path.
 */
public interface RtPath {
    /**
     * Try respond.
     *
     * @param line    Request line
     * @param headers Headers
     * @param body    Body
     * @return Response if passed routing rule
     */
    Optional<CompletableFuture<Response>> response(RequestLine line, Headers headers, Content body);
}
