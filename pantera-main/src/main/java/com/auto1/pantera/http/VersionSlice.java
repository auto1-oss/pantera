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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.misc.PanteraProperties;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Returns JSON with information about version of application.
 */
public final class VersionSlice implements Slice {

    private final PanteraProperties properties;

    public VersionSlice(final PanteraProperties properties) {
        this.properties = properties;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return ResponseBuilder.ok()
            .jsonBody(Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("version", this.properties.version()))
                .build()
            ).completedFuture();
    }
}
