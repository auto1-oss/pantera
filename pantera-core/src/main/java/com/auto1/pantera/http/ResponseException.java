/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

/**
 * Runtime exception carrying pre-built HTTP response.
 *
 * @since 1.0
 */
public final class ResponseException extends RuntimeException {

    private final Response response;

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
