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

import java.util.concurrent.CompletableFuture;

/**
 * Arti-pie slice.
 * <p>
 * Slice is a part of Pantera server.
 * Each Pantera adapter implements this interface to expose
 * repository HTTP API.
 * Pantera main module joins all slices together into solid web server.
 */
public interface Slice {

    /**
     * Respond to a http request.
     *
     * @param line    The request line
     * @param headers The request headers
     * @param body    The request body
     * @return The response.
     */
    CompletableFuture<Response> response(RequestLine line, Headers headers, Content body);

    /**
     * SliceWrap is a simple decorative envelope for Slice.
     */
    abstract class Wrap implements Slice {

        /**
         * Origin slice.
         */
        private final Slice slice;

        /**
         * @param slice Slice.
         */
        protected Wrap(final Slice slice) {
            this.slice = slice;
        }

        @Override
        public final CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            return this.slice.response(line, headers, body);
        }
    }
}
