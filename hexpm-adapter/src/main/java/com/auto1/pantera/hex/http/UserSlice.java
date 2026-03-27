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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.ResponseBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * This slice returns content about user in erlang format.
 */
public final class UserSlice implements Slice {
    /**
     * Path to users.
     */
    static final Pattern USERS = Pattern.compile("/users/(?<user>\\S+)");

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return ResponseBuilder.noContent().completedFuture();
    }
}
