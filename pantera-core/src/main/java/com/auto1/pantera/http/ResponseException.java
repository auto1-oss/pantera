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

/**
 * Runtime exception carrying pre-built HTTP response.
 *
 * @since 1.0
 */
public final class ResponseException extends RuntimeException {

    /**
     * The HTTP response. Marked {@code transient} because pantera never
     * serializes exceptions across the wire; this field exists only to
     * carry the in-process response back to the request handler.
     */
    private final transient Response response;

    /**
     * Ctor.
     *
     * @param response Response to return
     */
    public ResponseException(final Response response) {
        super(response.toString());
        this.response = response;
    }

    /**
     * Exception response.
     *
     * @return Response
     */
    public Response response() {
        return this.response;
    }
}
