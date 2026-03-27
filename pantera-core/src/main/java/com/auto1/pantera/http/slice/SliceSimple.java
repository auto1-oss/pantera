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

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Simple decorator for Slice.
 */
public final class SliceSimple implements Slice {

    private final Supplier<Response> res;

    public SliceSimple(Response response) {
        this.res = () -> response;
    }

    public SliceSimple(Supplier<Response> res) {
        this.res = res;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return CompletableFuture.completedFuture(this.res.get());
    }
}
