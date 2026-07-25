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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * {@code GET /npm} — the registry root. Previously an empty 200 stub
 * (a standing {@code @todo}); now returns a small, honest description of
 * this repository (name, backing registry, and the endpoints it actually
 * serves) instead of an empty body that told the caller nothing.
 *
 * @since 2.3.0
 */
public final class RegistryInfoSlice implements Slice {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param name Repository name
     */
    public RegistryInfoSlice(final String name) {
        this.name = name;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return body.asBytesFuture().thenApply(ignored -> {
            final JsonObject info = Json.createObjectBuilder()
                .add("pantera", true)
                .add("registry", this.name)
                .add("endpoints", Json.createObjectBuilder()
                    .add("ping", "/-/ping")
                    .add("whoami", "/-/whoami")
                    .add("search", "/-/v1/search")
                    .add("tokens", "/-/npm/v1/tokens")
                    .add("user", "/-/npm/v1/user")
                    .add("keys", "/-/npm/v1/keys")
                    .build())
                .build();
            return ResponseBuilder.ok().jsonBody(info).build();
        });
    }
}
